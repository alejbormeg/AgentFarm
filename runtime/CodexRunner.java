import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class CodexRunner {
  static final ObjectMapper JSON=new ObjectMapper();
  static final Path STATE=Path.of(System.getProperty("agentfarm.state",System.getProperty("user.home")+"/.local/state/agentfarm"));
  static final String ROOT=System.getProperty("agentfarm.legacy-repo",""), ACCOUNT=System.getProperty("agentfarm.account","codex-subscription");
  static final HttpClient HTTP=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
  static String auth,csrfHeader,csrf,worker,base;
  static volatile String thread,turn;
  static volatile Process server;
  static BufferedWriter input;
  static final Map<Integer,CompletableFuture<JsonNode>> pending=new ConcurrentHashMap<>();
  static final BlockingQueue<JsonNode> events=new LinkedBlockingQueue<>(10000);
  static int seq;
  static long windowStart,windowEnd;
  static int initialUsed,used;
  static boolean interrupted;
  static Map<String,Object> obj(Object... v){var m=new LinkedHashMap<String,Object>();for(int i=0;i<v.length;i+=2)m.put((String)v[i],v[i+1]);return m;}
  static synchronized void send(Object o)throws Exception{input.write(JSON.writeValueAsString(o));input.newLine();input.flush();}
  static JsonNode rpc(String method,Object params)throws Exception{
    int id; synchronized(CodexRunner.class){id=++seq;}
    var future=new CompletableFuture<JsonNode>();pending.put(id,future);
    send(obj("id",id,"method",method,"params",params));
    try { var r=future.get(25,TimeUnit.SECONDS);if(r.has("error"))throw new IOException(method+": "+r.get("error"));return r.path("result");}
    finally{pending.remove(id);}
  }
  static JsonNode api(String method,String path,Object body)throws Exception{return api(method,path,body,true);}
  static JsonNode api(String method,String path,Object body,boolean retry)throws Exception{
    var b=HttpRequest.newBuilder(URI.create(System.getProperty("agentfarm.url","http://localhost:8080")+"/api"+path)).timeout(Duration.ofSeconds(10)).header("Authorization",auth);
    if(method.equals("POST"))b.header(csrfHeader,csrf).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    else b.GET();
    var r=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    if(r.statusCode()==403&&method.equals("POST")&&retry){var token=api("GET","/csrf",null,false);csrfHeader=token.path("header").asText();csrf=token.path("token").asText();return api(method,path,body,false);}
    if(r.statusCode()!=200)throw new IOException(path+" HTTP "+r.statusCode()+": "+r.body());
    return JSON.readTree(r.body());
  }
  static JsonNode get(String p)throws Exception{return api("GET",p,null);}
  static JsonNode post(String p,Object b)throws Exception{return api("POST",p,b);}
  static void limits(boolean first)throws Exception{
    var response=rpc("account/rateLimits/read",null);
    JsonNode weekly=validateQuota(response);
    used=(int)Math.ceil(weekly.get("usedPercent").asDouble()*100);
    long end=weekly.get("resetsAt").asLong();
    if(first){windowEnd=end;windowStart=end-604800;initialUsed=used;}
    if(end!=windowEnd||Instant.now().getEpochSecond()>=end)throw new IOException("Quota window reset; manual restart required");
    if(used>=9800)throw new IOException("2% remaining threshold reached");
    Files.writeString(STATE.resolve("quota.json"),JSON.writeValueAsString(obj("weekly_used_percent",used/100.0,"stop_remaining_percent",2,"observed_at",Instant.now().toString(),"resets_at",windowEnd)));
  }
  static JsonNode validateQuota(JsonNode response)throws IOException{
    if(!response.path("ordinaryUsageAllowed").asBoolean(true))throw new IOException("Ordinary usage blocked");
    var bucket=response.path("rateLimitsByLimitId").path("codex");
    if(bucket.isMissingNode())bucket=response.path("rateLimits");
    JsonNode weekly=null;
    for(String key:List.of("primary","secondary")){
      var w=bucket.path(key);
      if(w.path("windowDurationMins").asLong()==10080)weekly=w;
      if(w.has("usedPercent")&&w.path("usedPercent").asDouble()>=98)throw new IOException("Quota window reached 98% used");
    }
    if(weekly==null||!weekly.has("usedPercent")||!weekly.has("resetsAt"))throw new IOException("Weekly quota unavailable");
    return weekly;
  }
  static void observe()throws Exception{post("/budget/observe",obj("account",ACCOUNT,"period","weekly","start",windowStart,"used",used,"observed_at",Instant.now().getEpochSecond()));}
  static void beat()throws Exception{
    var o=get("/overview");if(o.path("paused").asBoolean())throw new IOException("Operator paused farm");
    boolean enabled=false;
    for(var w:o.path("workers"))if(w.path("id").asText().equals(worker))enabled=w.path("enabled").asInt()==1&&w.path("node_enabled").asInt()==1;
    if(!enabled)throw new IOException("Worker or node disabled");
    for(var t:o.path("tasks"))if(t.path("id").asText().equals("FORJA-E0-001")&&t.path("cancel_requested").asInt()==1)throw new IOException("Cancellation requested");
    post("/heartbeat/node/napoleon-wsl",obj("state","active"));
    post("/heartbeat/worker/"+worker,obj("state","busy"));
  }
  static String git(String... args)throws Exception{
    var cmd=new ArrayList<String>(List.of("git","-C",ROOT));cmd.addAll(List.of(args));
    var p=new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String s=new String(p.getInputStream().readAllBytes());
    if(!p.waitFor(20,TimeUnit.SECONDS)||p.exitValue()!=0)throw new IOException("git failed: "+s);
    return s.trim();
  }
  static void startServer()throws Exception{
    var builder=new ProcessBuilder(System.getProperty("agentfarm.codex","codex"),"app-server").redirectError(STATE.resolve("codex-stderr.log").toFile());
    builder.environment().put("PATH",System.getProperty("user.home")+"/.local/bin:"+System.getenv("PATH"));
    for(String key:List.of("OPENAI_API_KEY","CODEX_API_KEY","ANTHROPIC_API_KEY"))builder.environment().remove(key);
    server=builder.start();
    input=new BufferedWriter(new OutputStreamWriter(server.getOutputStream()));
    Thread.ofPlatform().daemon().start(()->{
      try(var lines=new BufferedReader(new InputStreamReader(server.getInputStream()))){
        String line;while((line=lines.readLine())!=null){
          var n=JSON.readTree(line);
          if(n.has("id")&&!n.has("method")){var f=pending.get(n.path("id").asInt());if(f!=null)f.complete(n);}
          else if(n.has("method")){
            if(n.has("id")){send(obj("id",JSON.convertValue(n.get("id"),Object.class),"error",obj("code",-32601,"message","Unattended approvals are denied")));continue;}
            if(!events.offer(n))throw new IOException("Event queue overflow");
          }
        }
      }catch(Exception e){pending.values().forEach(f->f.completeExceptionally(e));}
    });
    rpc("initialize",obj("clientInfo",obj("name","agentfarm","title","AgentFarm WSL","version","0.1.0")));
    send(obj("method","initialized"));
  }
  static String runTurn(String prompt,String sandbox)throws Exception{
    limits(false);observe();beat();
    var t=rpc("thread/start",obj("cwd",ROOT,"approvalPolicy","never","sandbox",sandbox));
    thread=t.path("thread").path("id").asText();
    var r=rpc("turn/start",obj("threadId",thread,"input",List.of(obj("type","text","text",prompt))));
    turn=r.path("turn").path("id").asText();
    long started=System.nanoTime(),next=0;
    StringBuilder message=new StringBuilder();
    while(true){
      if(System.nanoTime()-started>Duration.ofMinutes(45).toNanos())throw new IOException("45 minute turn timeout");
      if(System.nanoTime()>=next){limits(false);observe();beat();next=System.nanoTime()+Duration.ofSeconds(5).toNanos();}
      var n=events.poll(1,TimeUnit.SECONDS);if(n==null){if(!server.isAlive())throw new IOException("Codex exited");continue;}
      String method=n.path("method").asText();
      if(method.equals("item/agentMessage/delta"))message.append(n.path("params").path("delta").asText());
      if(method.equals("turn/completed")&&n.path("params").path("threadId").asText().equals(thread)){
        var completed=n.path("params").path("turn");
        if(!completed.path("status").asText().equals("completed"))throw new IOException("Turn ended: "+completed.path("status").asText());
        turn=null;return message.toString();
      }
    }
  }
  public static void main(String[] args)throws Exception{
    if(args.length>0&&args[0].equals("guard-test")){
      for(String sample:List.of("{}",
          "{\"rateLimits\":{\"primary\":{\"usedPercent\":98,\"windowDurationMins\":10080,\"resetsAt\":9999999999}}}",
          "{\"rateLimits\":{\"primary\":{\"usedPercent\":99,\"windowDurationMins\":300},\"secondary\":{\"usedPercent\":50,\"windowDurationMins\":10080,\"resetsAt\":9999999999}}}")){
        boolean rejected=false;try{validateQuota(JSON.readTree(sample));}catch(IOException expected){rejected=true;}
        if(!rejected)throw new AssertionError("Unsafe quota admitted");
      }
      validateQuota(JSON.readTree("{\"rateLimits\":{\"primary\":{\"usedPercent\":97,\"windowDurationMins\":10080,\"resetsAt\":9999999999}}}"));
      System.out.println("Guard tests passed: missing quota, 98% weekly, secondary window exhaustion, 97% admission");return;
    }
    if(!Boolean.getBoolean("agentfarm.legacy-pilot")||ROOT.isBlank())throw new IOException("Historical pilot disabled; use the enrolled FarmDaemon executor");
    Files.createDirectories(STATE);
    try(var channel=java.nio.channels.FileChannel.open(STATE.resolve("runner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
      if(lock==null)throw new IOException("Another runner is active");
      auth="Basic "+Base64.getEncoder().encodeToString(("admin:"+Files.readString(STATE.resolve("panel-password")).trim()).getBytes());
      var token=get("/csrf");csrfHeader=token.path("header").asText();csrf=token.path("token").asText();
      try{
        startServer();
        limits(true);
        if(args.length>0&&args[0].equals("quota")){System.out.println("Weekly remaining: "+(100-used/100.0)+"%");return;}
        var accounts=get("/overview").path("budget_accounts");
        for(var a:accounts)if(a.path("account").asText().equals(ACCOUNT))throw new IOException("Existing account requires explicit policy reconciliation");
        post("/budget/configure",obj("account",ACCOUNT,"mode","subscription","shares",obj("weekly",9800)));
        post("/budget/open-window",obj("account",ACCOUNT,"period","weekly","start",windowStart,"end",windowEnd,"total",10000,"used",used,"observed_at",Instant.now().getEpochSecond(),"source","provider","max_age",30));
        post("/nodes",obj("id","napoleon-wsl","role","napoleon"));
        for(String id:List.of("boxer-forja","boxer-review"))post("/workers",obj("id",id,"node","napoleon-wsl"));
        worker="boxer-forja";
        post("/heartbeat/node/napoleon-wsl",obj("state","active"));post("/heartbeat/worker/"+worker,obj("state","idle"));
        post("/tasks",obj("id","FORJA-E0-001","project","forja","title","Época 0: contratos, validación y revisión con Codex en WSL","acceptance",List.of("Contratos v0 coherentes con README; validación reproducible; revisión independiente; sin publicar ni desplegar"),"allowed_paths",List.of("specs/","conformance/","docs/","scripts/","pom.xml",".mvn/","mvnw","mvnw.cmd",".github/","CHANGELOG.md","compatibility.md")));
        var assign=post("/assign",obj("project","forja","id","FORJA-E0-001","worker",worker,"account",ACCOUNT,"run_id","forja-e0-wsl","amounts",obj("weekly",9800-used)));
        if(!assign.path("state").asText().equals("assigned"))throw new IOException("Admission denied: "+assign);
        post("/tasks/forja/FORJA-E0-001/progress",obj("worker",worker,"progress",0));
        base=git("rev-parse","HEAD");
        Files.writeString(STATE.resolve("status.txt"),"running\n");
        String prompt=Files.readString(STATE.resolve("forja-prompt.txt"));
        String report=runTurn(prompt,"workspace-write");Files.writeString(STATE.resolve("boxer-result.md"),report);
        post("/heartbeat/worker/"+worker,obj("state","idle"));worker="boxer-review";
        post("/heartbeat/worker/"+worker,obj("state","busy"));
        String review=runTurn("Revisa de forma independiente el diff local y los contratos de época 0 de Forja frente a README.md. No modifiques archivos ni ejecutes comandos de escritura. Busca incoherencias de tenant, identidad, rutas, SSE, CloudEvents y validación. Entrega hallazgos con rutas y líneas, y pruebas que faltan. No publiques nada.","read-only");
        Files.writeString(STATE.resolve("review-result.md"),review);
        post("/heartbeat/worker/"+worker,obj("state","idle"));
        worker="boxer-forja";

        for(String path:git("diff","--name-only",base).split("\\n")) {
          if(path.isBlank())continue;
          if(!path.matches("(specs|conformance|docs|scripts|[.]mvn|[.]github)/.+|pom[.]xml|mvnw|mvnw[.]cmd|CHANGELOG[.]md|compatibility[.]md"))
            throw new IOException("Out of scope change: "+path);
        }
        git("add","-A");
        if(!git("diff","--cached","--name-only").isBlank())
          git("-c","user.name=AgentFarm","-c","user.email=agentfarm@localhost","commit","-m","feat: define epoch zero contracts and conformance validation");

        String sha=git("rev-parse","HEAD");
        post("/tasks/forja/FORJA-E0-001/candidate",obj("worker",worker,"sha",sha));
        limits(false);
        post("/budget/settle",obj("account",ACCOUNT,"run_id","forja-e0-wsl","actual",obj("weekly",Math.max(0,used-initialUsed))));
        Files.writeString(STATE.resolve("status.txt"),"review\n"+sha+"\n");
      }catch(Exception e){
        if(turn!=null)try{rpc("turn/interrupt",obj("threadId",thread,"turnId",turn));Thread.sleep(2000);}catch(Exception ignored){}
        try{post("/governance/paused/true",obj());}catch(Exception ignored){}
        Files.writeString(STATE.resolve("status.txt"),"paused: "+e.getMessage()+"\n");
        System.err.println("Paused: "+e.getMessage());
      }finally{
        if(worker!=null)try{post("/heartbeat/worker/"+worker,obj("state","idle"));}catch(Exception ignored){}
        if(server!=null){server.descendants().forEach(ProcessHandle::destroy);server.destroy();if(!server.waitFor(10,TimeUnit.SECONDS)){server.descendants().forEach(ProcessHandle::destroyForcibly);server.destroyForcibly();}}
      }
    }
  }
}

