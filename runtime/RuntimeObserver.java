import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public class RuntimeObserver {
  static final ObjectMapper J=new ObjectMapper();
  static final Path STATE=Path.of(System.getProperty("user.home"),".local/state/agentfarm");
  static final Path ROOT=Path.of("/home/alex/proyectos/forja");
  static final HttpClient HTTP=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(4)).build();
  static String secret,auth,csrfHeader,csrf;
  static final Set<String> seen=new HashSet<>();
  static final List<Map<String,Object>> backlog=new ArrayList<>();
  static Map<String,Object> obj(Object... x){var m=new LinkedHashMap<String,Object>();for(int i=0;i<x.length;i+=2)m.put((String)x[i],x[i+1]);return m;}
  static JsonNode request(String path,Object value)throws Exception{
    var b=HttpRequest.newBuilder(URI.create("http://localhost:8080/api"+path)).timeout(Duration.ofSeconds(6)).header("Authorization",auth);
    if(value!=null)b.header(csrfHeader,csrf).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(J.writeValueAsString(value)));else b.GET();
    var r=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    if(r.statusCode()!=200)throw new IOException("Telemetry HTTP "+r.statusCode());
    return J.readTree(r.body());
  }
  static String safe(String text){
    return text.replace(secret,"[contraseña oculta]")
      .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*","Bearer [oculto]")
      .replaceAll("sk-[A-Za-z0-9_-]{12,}","[clave oculta]")
      .replaceAll("\\x1B\\[[0-9;]*[A-Za-z]","");
  }
  static String content(JsonNode n){
    if(n.isTextual())return n.asText();
    if(n.isArray()){var a=new ArrayList<String>();for(var c:n)if(c.has("text")){
      String text=c.path("text").asText();
      try { var parsed=J.readTree(text);if(parsed.has("output")&&parsed.get("output").isTextual())text=parsed.get("output").asText(); } catch(Exception ignored) {}
      a.add(text);
    }return String.join("\n",a);}
    return n.toString();
  }
  static List<Path> sessions()throws Exception{
    var root=Path.of(System.getProperty("user.home"),".codex/sessions",LocalDate.now().getYear()+"",String.format("%02d",LocalDate.now().getMonthValue()),String.format("%02d",LocalDate.now().getDayOfMonth()));
    if(!Files.isDirectory(root))return List.of();
    List<Path> result=new ArrayList<>();
    try(var files=Files.list(root)){
      for(var p:files.filter(p->p.toString().endsWith(".jsonl")).sorted().toList()){
        try(var r=Files.newBufferedReader(p)){
          var first=J.readTree(r.readLine()).path("payload");
          if(first.path("cwd").asText().equals(ROOT.toString())&&first.path("originator").asText().equals("agentfarm"))result.add(p);
        }
      }
    }
    return result;
  }
  static String command(String... args)throws Exception{
    var p=new ProcessBuilder(args).redirectErrorStream(true).start();
    byte[] bytes=p.getInputStream().readNBytes(100000);
    if(!p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Probe timeout");}
    return new String(bytes,StandardCharsets.UTF_8).trim();
  }
  static Map<String,Object> collect()throws Exception{
    var files=sessions();
    Map<String,Object> snapshot=obj("node","napoleon-wsl","host","WSL · Ubuntu","distro","Ubuntu 24.04 LTS","repository",ROOT.toString(),"source","Proceso Linux y eventos locales de codex app-server; observador de solo lectura","observed_at",Instant.now().toString(),"run_state",Files.readString(STATE.resolve("status.txt")).trim(),"implementation_done",Files.exists(STATE.resolve("boxer-result.md")),"review_done",Files.exists(STATE.resolve("review-result.md")));
    var supervisors=ProcessHandle.allProcesses().filter(p->p.info().commandLine().orElse("").contains("/runtime/CodexRunner.java")).toList();
    if(!supervisors.isEmpty()){
      var supervisor=supervisors.getFirst();snapshot.put("supervisor_pid",supervisor.pid());
      supervisor.descendants().forEach(p->{
        String exe=p.info().command().orElse("");
        if(exe.endsWith("/bin/codex"))snapshot.put("codex_pid",p.pid());
        if(exe.endsWith("/codex-code-mode-host"))snapshot.put("code_host_pid",p.pid());
      });
    }
    snapshot.put("branch",command("git","-C",ROOT.toString(),"branch","--show-current"));
    String baseline=command("git","-C",ROOT.toString(),"rev-list","--max-parents=0","HEAD").lines().findFirst().orElseThrow();
    var changes=java.util.stream.Stream.concat(
      command("git","-C",ROOT.toString(),"diff","--name-status",baseline).lines(),
      command("git","-C",ROOT.toString(),"ls-files","--others","--exclude-standard").lines().map(p->"? "+p)).filter(x->!x.isBlank()).distinct().limit(150).toList();
    snapshot.put("changed_files",changes);snapshot.put("files_count",changes.size());
    if(Files.exists(STATE.resolve("quota.json")))snapshot.put("quota",J.readTree(Files.readString(STATE.resolve("quota.json"))));
    String lastAt="",phase="Sin actividad registrada";int sessionIndex=0;
    for(var p:files){
      if(Files.size(p)>20000000)throw new IOException("Session exceeds observer read limit");
      String id=p.getFileName().toString();String worker=sessionIndex++==0?"boxer-forja":"boxer-review";long line=0;
      try(var reader=Files.newBufferedReader(p)){
        String raw;
        while((raw=reader.readLine())!=null){
          line++;JsonNode n;try{n=J.readTree(raw);}catch(Exception incomplete){continue;}
          var y=n.path("payload");String outer=n.path("type").asText(),type=y.path("type").asText();
          if(outer.equals("session_meta")){
            snapshot.put("session",y.path("id").asText());snapshot.put("cli_version",y.path("cli_version").asText());continue;
          }
          if(outer.equals("turn_context")){snapshot.put("model",y.path("model").asText());continue;}
          String kind=null,text=null;
          if(outer.equals("response_item")){
            if(type.equals("custom_tool_call")||type.equals("function_call")){kind="command";text=y.path("name").asText()+"\n"+(y.has("input")?content(y.get("input")):content(y.path("arguments")));phase="Ejecutando herramientas";}
            if(type.equals("custom_tool_call_output")||type.equals("function_call_output")){kind="output";text=content(y.path("output"));phase="Herramienta finalizada";}
            if(type.equals("message")&&y.path("role").asText().equals("assistant")){kind="message";text=content(y.path("content"));phase="Actualización de Codex";}
          }
          if(outer.equals("event_msg")&&Set.of("task_started","task_complete","turn_aborted").contains(type)){kind="lifecycle";text=type;phase=type.equals("task_started")?"Turno de Codex iniciado":"Turno de Codex finalizado";}
          if(kind==null||text==null||text.isBlank())continue;
          String at=n.path("timestamp").asText();
          if(at.compareTo(lastAt)>=0){lastAt=at;snapshot.put("active_worker",worker);}
          String external=id+":"+line;
          if(!seen.contains(external)&&backlog.stream().noneMatch(e->external.equals(e.get("external_id")))){
            text=safe(text);if(text.length()>5500)text=text.substring(0,5500)+"\n[Salida recortada a 5500 caracteres]";
            backlog.add(obj("external_id",external,"at",at,"worker",worker,"kind",kind,"text",text));
          }
        }
      }
    }
    snapshot.put("last_event_at",lastAt);
    snapshot.put("phase",phase);
    return snapshot;
  }
  public static void main(String[] args)throws Exception{
    secret=Files.readString(STATE.resolve("panel-password")).trim();
    auth="Basic "+Base64.getEncoder().encodeToString(("admin:"+secret).getBytes(StandardCharsets.UTF_8));
    while(!Thread.currentThread().isInterrupted()){
      try{
        var token=request("/csrf",null);csrfHeader=token.path("header").asText();csrf=token.path("token").asText();
        var snapshot=collect();
        backlog.sort(Comparator.comparing(e->e.get("at").toString()));
        var batch=new ArrayList<>(backlog.subList(0,Math.min(30,backlog.size())));
        request("/runtime/observations",obj("snapshot",snapshot,"events",batch));
        for(var e:batch)seen.add(e.get("external_id").toString());
        backlog.subList(0,batch.size()).clear();
      }catch(Exception e){System.err.println(Instant.now()+" Observador sin conexión: "+e.getClass().getSimpleName()+" "+e.getMessage());}
      Thread.sleep(2000);
    }
  }
}

