class AgentFarmMonitor extends HTMLElement {
  connectedCallback() {
    if(this.ready){this.poll();this.timer=setInterval(()=>this.poll(),2000);return;}this.ready=true;this.events=[];this.filter="";this.follow=true;this.openIds=new Set();
    this.attachShadow({mode:"open"});
    this.shadowRoot.innerHTML=`
      <style>
        :host{display:block;width:100%;color:#252c35;font:14px/1.5 Arial,sans-serif;--ink:#142d4e;--blue:#285bc5;--muted:#596572}
        *{box-sizing:border-box}h2,h3,p{margin:0}h2{font-size:24px;color:var(--ink);line-height:1.25}h3{font-size:16px;color:var(--ink)}small,.muted{color:var(--muted)}button,select,input{font:inherit}button,select{background:white;border:1px solid #aab2bc;border-radius:5px;padding:8px 12px;min-height:40px;color:var(--ink)}
        button{cursor:pointer}button:hover{background:#eaf0fb}button:disabled{opacity:.5;cursor:wait}button:focus-visible,select:focus-visible,input:focus-visible,summary:focus-visible,a:focus-visible{outline:3px solid var(--blue);outline-offset:3px}a{color:var(--blue);text-underline-offset:3px}.primary{background:var(--blue);border-color:var(--blue);color:white}.primary:hover{background:#1d4598}.danger{color:#9b2f2b;border-color:#bfa9a5}.toolbar,.topline,.inline{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.topline{justify-content:space-between;align-items:flex-start}.toolbar{margin-top:18px}.badge{display:inline-flex;align-items:center;gap:6px;border-radius:4px;padding:4px 8px;background:#e7edf4;color:var(--ink);font-weight:600;font-size:12px}.good{background:#deeee5;color:#205d3d}.warn{background:#fff0d4;color:#77500c}.bad{background:#f7dfdc;color:#8c2924}.status{margin:12px 0;color:var(--muted)}.alert{padding:12px 16px;background:#fff0d4;color:#62400b;border:1px solid #d8bc83;margin:14px 0}.alert[hidden]{display:none}.layout{display:grid;grid-template-columns:minmax(0,1fr) 310px;gap:28px;margin-top:24px}.section{padding:20px 0;border-top:1px solid #ccd2d9}.section:first-child{padding-top:0;border-top:0}.layout>main,.side{min-width:0}.side{border-left:1px solid #ccd2d9;padding-left:24px}.sub{margin-top:5px;color:var(--muted);max-width:75ch}.phases{display:flex;list-style:none;padding:0;margin:18px 0;gap:0;flex-wrap:wrap}.phases li{padding:8px 14px 8px 0;margin-right:14px;color:var(--muted);border-bottom:2px solid #ccd2d9}.phases .done{color:#205d3d;border-color:#36714e}.phases .current{font-weight:700;color:var(--blue);border-color:var(--blue)}.tablewrap{overflow-x:auto;margin-top:12px}table{width:100%;border-collapse:collapse;text-align:left}th{color:var(--muted);font-size:12px;font-weight:600}td,th{padding:10px 12px 10px 0;vertical-align:top;border-bottom:1px solid #e0e3e8}td{min-width:110px}td:nth-child(2){min-width:220px}td strong{display:block;color:var(--ink)}code,pre,.mono{font:12px/1.6 ui-monospace,SFMono-Regular,Consolas,monospace;overflow-wrap:anywhere}dt{color:var(--muted);font-size:12px;margin-top:14px}dd{margin:2px 0 0;overflow-wrap:anywhere}details.proof{margin-top:18px}summary{cursor:pointer;color:var(--ink)}.consolebar{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:12px 0}.consolebar label{display:flex;gap:6px;align-items:center}.console{height:410px;overflow:auto;background:#152232;color:#e4eaf2;border:1px solid #293d55;border-radius:5px;padding:8px 14px;scrollbar-color:#798ca3 #152232}.console:focus-visible{outline:3px solid var(--blue);outline-offset:3px}.console details{padding:9px 0;border-bottom:1px solid #314154}.console summary{color:#e4eaf2;font:12px/1.6 ui-monospace,SFMono-Regular,Consolas,monospace;white-space:normal;overflow-wrap:anywhere}.console pre{margin:8px 0 6px;white-space:pre-wrap;color:#d8e2ee}.console .command{color:#a9c8ff}.console .message{color:#bfe6d0}.console .output{color:#d8e2ee}.empty{padding:24px;color:var(--muted)}.console .empty{color:#c4ced9}.files{max-height:260px;overflow:auto;padding-left:18px}.files li{padding:3px 0;overflow-wrap:anywhere}progress{width:100%;accent-color:var(--blue);height:10px}.quota{font-size:16px;font-weight:600;color:var(--ink);margin:12px 0 4px}.counts{padding:12px 0;border-top:1px solid #ccd2d9;border-bottom:1px solid #ccd2d9;margin-top:16px}.counts span{margin-right:20px;display:inline-block}#action-message{margin-top:8px}#action-message:empty{display:none}#error{white-space:pre-wrap}
        @media(max-width:900px){.layout{grid-template-columns:1fr}.side{padding-left:0;border-left:0;border-top:1px solid #ccd2d9;padding-top:24px}.console{height:360px}.topline h2{font-size:21px}.toolbar{gap:8px}.toolbar button{flex-grow:1}.phases li{font-size:12px;margin-right:9px}td,th{padding-right:18px}}
        @media(max-width:600px){.tablewrap{overflow:visible}table,tbody,tr,td{display:block;width:100%}thead{display:none}tr{padding:12px 0;border-bottom:1px solid #ccd2d9}td{min-width:0!important;border:0;padding:6px 0}td:before{content:attr(data-label);display:block;color:var(--muted);font-size:12px;margin-bottom:3px}.consolebar{align-items:flex-start;flex-direction:column}.consolebar label{max-width:100%}.consolebar select{max-width:240px}.console{height:380px}.counts{font-size:13px}}
        @media(prefers-reduced-motion:no-preference){button{transition:background .15s}}
      </style>
      <div class="topline"><div><h2 id="heading">Trabajo en curso</h2><p class="sub" id="lead">Conectando con el control plane…</p></div><span class="badge" id="connection" role="status">Conectando</span></div>
      <p class="status" id="updated" aria-live="off"></p>
      <div class="alert" id="error" role="status" hidden></div>
      <div class="toolbar"><button id="pause" class="danger">Pausar agentes</button><button id="resume">Habilitar admisión</button><button id="cancel">Solicitar cancelación</button><button id="refresh">Actualizar ahora</button><button id="jump">Ver consola</button></div>
      <p id="action-message" role="status"></p>
      <div class="counts" id="counts"></div>
      <div class="layout"><main>
        <section class="section"><h3>Tarea y avance observable</h3><p class="sub">Las fases proceden de los turnos registrados. No estimamos un porcentaje de ejecución.</p><ol class="phases" id="phases"></ol><div class="tablewrap"><table><thead><tr><th>Proyecto / tarea</th><th>Qué se está resolviendo</th><th>Responsable / estado</th></tr></thead><tbody id="tasks"></tbody></table></div></section>
        <section class="section"><h3>Workers y dirección</h3><div class="tablewrap"><table><thead><tr><th>Agente</th><th>Trabajo actual</th><th>Señal observable</th></tr></thead><tbody id="workers"></tbody></table></div></section>
        <section class="section"><h3>Consola de actividad Codex</h3><p class="sub">Eventos y salidas de herramientas registrados en WSL. Solo lectura; se omiten prompts internos y razonamiento privado.</p>
          <div class="consolebar"><label>Agente <select id="worker-filter"><option value="">Todos</option><option value="boxer-forja">Boxer · implementación</option><option value="boxer-review">Boxer · revisión</option></select></label><label>Mostrar <select id="kind-filter"><option value="">Toda la actividad</option><option value="message">Mensajes de Codex</option><option value="command">Comandos y herramientas</option><option value="output">Salidas</option></select></label><label><input id="follow" type="checkbox" checked>Seguir últimas líneas</label></div>
          <div id="console" class="console" role="region" aria-label="Consola de actividad de Codex" tabindex="0"><p class="empty">Esperando eventos del observador WSL.</p></div><p class="sub"><small>Se muestran hasta 200 eventos recientes. Abre una línea para ver su contenido.</small></p>
        </section>
      </main><aside class="side">
        <section class="section"><h3>Conexión real</h3><dl id="proof"></dl><p class="sub"><small>Observación local del proceso y su sesión. No acredita la calidad del código.</small></p></section>
        <section class="section"><h3>Clover · cuota Codex</h3><p id="quota-label" class="quota">Sin lectura de cuota</p><progress id="quota-bar" max="100" value="0" aria-label="Cuota semanal utilizada"></progress><p class="sub" id="quota-note"></p></section>
        <section class="section"><h3>Cambios en Forja</h3><p class="sub" id="files-label"></p><ul id="files" class="files mono"></ul><p class="sub"><small>Cambios locales; todavía no equivalen a una PR aceptada.</small></p></section>
      </aside></div>`;
    this.shadowRoot.querySelectorAll("button").forEach(b=>b.type="button");
    this.shadowRoot.addEventListener("click",event=>event.stopPropagation());
    this.shadowRoot.addEventListener("change",event=>event.stopPropagation());
    this.$=id=>this.shadowRoot.getElementById(id);
    this.$("jump").onclick=()=>{this.$("console").scrollIntoView({block:"start",behavior:"instant"});this.$("console").focus({preventScroll:false});};
    this.$("refresh").onclick=()=>this.poll();
    this.$("pause").onclick=()=>this.action("/governance/paused/true",{},"Pausa solicitada. El supervisor interrumpirá el turno en su próxima comprobación.");
    this.$("resume").onclick=()=>this.action("/governance/paused/false",{},"Admisión habilitada. Un ejecutor detenido necesita reconciliación; no se reinicia automáticamente.");
    this.$("cancel").onclick=()=>{if(this.task)this.action("/tasks/"+encodeURIComponent(this.task.project)+"/"+encodeURIComponent(this.task.id)+"/cancel",{},"Cancelación solicitada. Esperando confirmación del supervisor.");};
    for(const id of ["worker-filter","kind-filter"])this.$(id).onchange=()=>this.renderEvents(true);
    this.$("follow").onchange=e=>{this.follow=e.target.checked;if(this.follow)this.$("console").scrollTop=this.$("console").scrollHeight;};
    this.poll();this.timer=setInterval(()=>this.poll(),2000);
  }
  disconnectedCallback(){clearInterval(this.timer);this.controller?.abort();}
  async json(path,options={}){const r=await fetch("/api"+path,{credentials:"same-origin",cache:"no-store",signal:AbortSignal.timeout(8000),...options});if(!r.ok)throw new Error(r.status===401||r.status===403?"La sesión ha caducado. Recarga e inicia sesión.":"El servidor no responde correctamente (HTTP "+r.status+").");return r.json();}
  async poll(){
    if(this.loading||!this.isConnected)return;this.loading=true;
    try{const [runtime,overview]=await Promise.all([this.json("/runtime"),this.json("/overview")]);this.data=runtime;this.overview=overview;this.render();this.$("error").hidden=true;}
    catch(e){this.$("error").textContent=e.message+" Los datos visibles pueden estar desactualizados.";this.$("error").hidden=false;this.$("connection").textContent="Sin conexión con el panel";this.$("connection").className="badge bad";}
    finally{this.loading=false;}
  }
  async action(path,body,message){
    const buttons=[...this.shadowRoot.querySelectorAll("button")];buttons.forEach(b=>b.disabled=true);
    try{const token=await this.json("/csrf");await this.json(path,{method:"POST",headers:{"Content-Type":"application/json",[token.header]:token.token},body:JSON.stringify(body)});this.$("action-message").textContent=message;await this.poll();}
    catch(e){this.$("action-message").textContent=e.message;}
    finally{buttons.forEach(b=>b.disabled=false);if(this.data)this.render();}
  }
  text(id,value){this.$(id).textContent=value??"—";}
  time(value){if(!value)return"Sin eventos";const d=new Date(value);return isNaN(d)?"Desconocido":d.toLocaleTimeString("es-ES",{hour:"2-digit",minute:"2-digit",second:"2-digit"});}
  node(tag,text,cls){const e=document.createElement(tag);if(text!=null)e.textContent=text;if(cls)e.className=cls;return e;}
  render(){
    const d=this.data,o=this.overview,s=d.snapshot||{},tasks=o.tasks||[],workers=o.workers||[];
    const alive=d.fresh&&!!s.codex_pid&&String(s.run_state).startsWith("running");
    const current=s.active_worker||(!s.implementation_done?"boxer-forja":"boxer-review");
    this.text("heading",alive?"Trabajo en curso":tasks.some(t=>t.state==="review")?"Trabajo pendiente de aceptación":"Estado de la granja");
    this.task=tasks.find(t=>["assigned","running"].includes(t.state));
    const task=this.task||tasks[0];
    let title="Sin tareas registradas";try{title=JSON.parse(task?.spec||"{}").title||title;}catch{}
    this.text("lead",task?task.project+" · "+task.id+" · "+title:"Encola una tarea en Tareas para comenzar.");
    this.text("connection",!d.connected?"Sin observador WSL":!d.fresh?"Telemetría WSL caducada":alive?"Codex observado en WSL":s.session?"Codex finalizado · registros disponibles":"WSL conectado · sin sesión Codex");
    this.$("connection").className="badge "+(alive?"good":d.fresh?"warn":"bad");
    this.text("updated",d.connected?"Observador recibido hace "+d.age_seconds+" s · Última actividad Codex "+this.time(s.last_event_at)+" · Actualización cada 2 s":"Todavía no se ha recibido telemetría del ejecutor.");
    this.text("counts",(o.paused?"Admisión pausada":"Admisión habilitada")+" · "+workers.length+" workers registrados · "+tasks.filter(t=>["assigned","running"].includes(t.state)).length+" tarea en ejecución · "+tasks.filter(t=>t.state==="review").length+" pendiente de aceptación");
    this.$("pause").disabled=o.paused;this.$("resume").disabled=!o.paused;this.$("cancel").disabled=!this.task;
    const stages=[["Preparación",!!s.session],["Implementación",!!s.implementation_done],["Revisión independiente",!!s.review_done],["Aceptación humana",false]];
    this.$("phases").replaceChildren(...stages.map(([label,done],i)=>this.node("li",label,done?"done":i===stages.findIndex(x=>!x[1])?"current":"")));
    this.$("tasks").replaceChildren(...tasks.map(t=>{
      const tr=this.node("tr");const id=this.node("td");id.append(this.node("strong",t.project+" / "+t.id));let spec={};try{spec=JSON.parse(t.spec)}catch{}
      const desc=this.node("td");desc.append(this.node("strong",spec.title||t.id),this.node("small",(spec.acceptance||[]).join(" · ")));
      const work=this.node("td");const labels={running:"En ejecución",assigned:"Asignada",ready:"En cola",review:"Pendiente de aceptación",cancelled:"Cancelada"};
      work.append(this.node("strong",labels[t.state]||t.state),this.node("div",t.state==="running"&&alive?current:(t.worker||"Sin asignar")),this.node("small",t.state==="running"?"Avance: "+(!d.fresh?"telemetría caducada":!alive?"ejecutor detenido; revisar resultado":s.implementation_done?"implementación terminada; revisión en curso":"implementación en curso")+" · porcentaje no estimado":t.candidate_sha?"Candidato "+t.candidate_sha.slice(0,12):"Sin porcentaje verificado"));

      if(t.cancel_requested)work.append(this.node("div","Cancelación solicitada","badge warn"));tr.append(id,desc,work);return tr;
    }));
    const status={busy:"Ocupado",idle:"Disponible",unknown:"Sin heartbeat",stale:"Heartbeat caducado",node_unavailable:"Nodo no disponible",disabled:"Deshabilitado"};
    this.$("workers").replaceChildren(...[
      (()=>{const tr=this.node("tr");tr.append(this.node("td","Napoleon · WSL"),this.node("td","Supervisa cuota, pausa y turnos; dirección determinista"),this.node("td",s.supervisor_pid&&d.fresh?"Supervisor Java · PID "+s.supervisor_pid:String(s.run_state).startsWith("review")?"Turnos finalizados; esperando aceptación":"Sin proceso de dirección observado"));return tr;})(),
      ...workers.map(w=>{const tr=this.node("tr");let work="Esperando asignación";
        if(w.id==="boxer-forja")work=s.implementation_done?"Turno de implementación finalizado":alive&&current===w.id?"Forja · contratos y conformidad":work;
        if(w.id==="boxer-review")work=s.review_done?"Revisión finalizada":alive&&current===w.id?"Forja · revisión independiente":"Pendiente del resultado de implementación";
        tr.append(this.node("td",w.id),this.node("td",work),this.node("td",alive&&current===w.id?"Codex "+(s.cli_version||"")+" · PID "+s.codex_pid:(s.review_done?"Turnos registrados · ejecutor detenido":status[w.status]||w.status)));return tr;})
    ]);
    const fields=[["Entorno",s.host||"Desconocido"],["Distribución",s.distro||"Desconocida"],["Repositorio",s.repository||"Sin ruta observada"],["Rama",s.branch||"—"],["Modelo de la sesión",s.model||"Sin modelo observado"],["Ejecutable Codex",s.codex_pid?"Linux · PID "+s.codex_pid:"Sin proceso observado"],["Code Mode",s.code_host_pid?"PID "+s.code_host_pid:"Sin proceso observado"],["Sesión",s.session||"Sin sesión"],["Estado del supervisor",String(s.run_state).startsWith("review")?"Finalizado · pendiente de aceptación":s.run_state||"Sin estado"],["Última actividad",this.time(s.last_event_at)]];
    this.$("proof").replaceChildren(...fields.flatMap(([k,v])=>[this.node("dt",k),this.node("dd",v,k==="Sesión"||k==="Repositorio"?"mono":"")]));
    const q=s.quota||{},used=Number(q.weekly_used_percent);const quotaAge=q.observed_at?Math.floor((Date.now()-Date.parse(q.observed_at))/1000):null;
    this.text("quota-label",Number.isFinite(used)?(100-used).toLocaleString("es-ES")+" % semanal restante":"Sin lectura de cuota");
    this.$("quota-bar").value=Number.isFinite(used)?used:0;
    this.text("quota-note","Parada configurada: 2 % restante. "+(quotaAge===null?"Sin fecha de lectura.":"Lectura de hace "+quotaAge+" s"+(quotaAge>15?" · desactualizada.":"."))+" Las lecturas discretas pueden saltar el umbral.");
    const files=s.changed_files||[];this.text("files-label",files.length?files.length+" rutas con cambios locales":"Sin cambios sin confirmar en Git.");
    this.$("files").replaceChildren(...files.map(f=>this.node("li",f)));
    this.shadowRoot.querySelectorAll("table").forEach(table=>{
      const labels=[...table.querySelectorAll("th")].map(h=>h.textContent);
      table.querySelectorAll("tbody tr").forEach(row=>[...row.children].forEach((cell,i)=>cell.dataset.label=labels[i]));
    });
    this.events=d.events||[];this.renderEvents(false);
  }
  renderEvents(force){
    const w=this.$("worker-filter").value,k=this.$("kind-filter").value;
    const filtered=this.events.filter(e=>(!w||e.worker===w)&&(!k||e.kind===k));
    const key=w+"|"+k+"|"+filtered.map(e=>e.id).join(",");if(!force&&key===this.eventsKey)return;this.eventsKey=key;
    const box=this.$("console"),old=box.scrollTop;
    box.replaceChildren(...filtered.map(e=>{
      const row=this.node("details");row.open=this.openIds.has(e.id);row.addEventListener("toggle",()=>{row.open?this.openIds.add(e.id):this.openIds.delete(e.id);});
      const kind={command:"COMANDO",output:"SALIDA",message:"CODEX",lifecycle:"TURNO",supervisor:"DIRECCIÓN",error:"ERROR"}[e.kind]||e.kind;
      const preview=e.text.replace(/\\n/g," ").slice(0,130);
      row.setAttribute("aria-label",this.time(e.at)+" "+e.worker+" "+kind);
      row.append(this.node("summary",this.time(e.at)+" · "+e.worker+" · "+kind+" · "+preview,e.kind),this.node("pre",e.text));
      return row;
    }));
    if(!filtered.length)box.append(this.node("p","No hay eventos para este filtro. Prueba «Todos» o espera al próximo turno.","empty"));
    box.scrollTop=this.follow?box.scrollHeight:old;
  }
}
customElements.define("agentfarm-monitor",AgentFarmMonitor);

