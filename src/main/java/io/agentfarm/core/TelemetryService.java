package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TelemetryService {
  private final Store store;
  private final Clock clock;
  public TelemetryService(Store store, Clock clock) {
    this.store=store;this.clock=clock;
    store.tx(c->{
      statement(c,"CREATE TABLE IF NOT EXISTS runtime_snapshot(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL,received_at INTEGER NOT NULL)");
      statement(c,"CREATE TABLE IF NOT EXISTS runtime_events(id INTEGER PRIMARY KEY,external_id TEXT NOT NULL UNIQUE,at TEXT NOT NULL,worker TEXT NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL)");
      return null;
    });
  }
  public static String clean(String value) {
    return value.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]","")
      .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","")
      .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*","Bearer [oculto]")
      .replaceAll("(?:gh[pousr]_[A-Za-z0-9_]{12,}|afn_[A-Za-z0-9_-]{40,})","[credencial oculta]")
      .replaceAll("sk-[A-Za-z0-9_-]{12,}","[clave oculta]")
      .replaceAll("(?i)([\\\"']?(?:access_token|refresh_token|id_token|password|api_key|authorization)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',}]+","$1[oculto]");
  }
  private static String field(JsonNode n,String key,int max) {
    String v=text(n,key);
    if(v.length()>max)throw new IllegalArgumentException("Telemetry field too long: "+key);
    return clean(v);
  }
  public void ingest(JsonNode n) {
    if(encode(n).length()>262144)throw new IllegalArgumentException("Telemetry batch too large");
    var snapshot=n.path("snapshot");
    if(!snapshot.isObject())throw new IllegalArgumentException("Snapshot required");
    var accepted=MAPPER.createObjectNode();
    for(String k:List.of("node","host","distro","repository","branch","run_state","phase","cli_version","model","session","observed_at","last_event_at","supervisor_pid","codex_pid","code_host_pid","implementation_done","review_done","quota","changed_files","files_count","source","active_worker","executor_available","task_id","task_title","github_error")) {
      var v=snapshot.get(k);if(v==null)continue;
      if(encode(v).length()>50000)throw new IllegalArgumentException("Snapshot field too large");
      if(v.isTextual())accepted.put(k,clean(v.asText()));else accepted.set(k,v);
    }
    var events=n.path("events");
    if(!events.isArray()||events.size()>50)throw new IllegalArgumentException("At most 50 events per batch");
    List<Map<String,String>> checked=new ArrayList<>();
    for(var e:events) {
      String at=field(e,"at",40);
      java.time.Instant.parse(at);
      String worker=field(e,"worker",80);ControlService.identifier(worker);
      String kind=field(e,"kind",40);
      if(!Set.of("command","output","message","lifecycle","supervisor","error").contains(kind))throw new IllegalArgumentException("Unknown event kind");
      checked.add(Map.of("external_id",field(e,"external_id",180),"at",at,"worker",worker,"kind",kind,"text",field(e,"text",6000)));
    }
    long now=clock.instant().getEpochSecond();
    store.tx(c->{
      exec(c,"INSERT INTO runtime_snapshot VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,received_at=excluded.received_at",encode(accepted),now);
      for(var e:checked)exec(c,"INSERT OR IGNORE INTO runtime_events(external_id,at,worker,kind,text) VALUES(?,?,?,?,?)",e.get("external_id"),e.get("at"),e.get("worker"),e.get("kind"),e.get("text"));
      exec(c,"DELETE FROM runtime_events WHERE id < COALESCE((SELECT id FROM runtime_events ORDER BY id DESC LIMIT 1 OFFSET 599),0)");
      return null;
    });
  }
  public Map<String,Object> snapshot() {
    return store.tx(c->{
      var row=one(c,"SELECT * FROM runtime_snapshot WHERE id=1");
      var result=obj("connected",false,"fresh",false,"age_seconds",null,"snapshot",Map.of(),"events",rows(c,"SELECT * FROM (SELECT id,at,worker,kind,text FROM runtime_events ORDER BY id DESC LIMIT 200) ORDER BY id"));
      if(row!=null){
        long age=clock.instant().getEpochSecond()-((Number)row.get("received_at")).longValue();
        result.put("connected",true);result.put("fresh",age>=0&&age<=12);
        result.put("age_seconds",age);
        var snapshot=parse(row.get("payload").toString());
        result.put("snapshot",snapshot);
        boolean quotaFresh=false;
        try { long qage=clock.instant().getEpochSecond()-java.time.Instant.parse(snapshot.path("quota").path("observed_at").asText()).getEpochSecond();
          quotaFresh=age>=0&&age<=12&&qage>=0&&qage<=60&&snapshot.path("quota").path("weekly_used_percent").isNumber();
        } catch(Exception ignored){}
        result.put("quota_fresh",quotaFresh);
      }
      return result;
    });
  }
}

