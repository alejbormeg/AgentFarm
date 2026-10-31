class AgentFarmRegistry extends HTMLElement {
  routeView(){
    const name=location.pathname.split("/").filter(Boolean).at(-1);
    return ["nodes","workers","tasks","budgets","governance"].includes(name)?name:this.getAttribute("view");
  }
  reclaimSlot(){
    // Mateu beta can retain an old Element definition in the same render slot.
    this.parentElement?.querySelectorAll(":scope > agentfarm-registry").forEach(el=>{if(el!==this)el.remove();});
  }
  static get observedAttributes(){return ["view"];}
  attributeChangedCallback(name,previous,current){
    if(!this.ready)return;
    this.reclaimSlot();
    const next=this.routeView();if(next===this.view)return;
    this.view=next;this.signature=null;
    this.$("content").replaceChildren();
    this.$("state").textContent="Cargando vista";
    this.$("time").textContent="";
    this.poll();
  }
  connectedCallback(){
    this.reclaimSlot();
    if(this.ready){this.view=this.routeView();this.signature=null;this.poll();this.timer=setInterval(()=>this.poll(),2000);return;}
    this.ready=true;this.view=this.routeView();this.attachShadow({mode:"open"});
    this.shadowRoot.innerHTML=`
      <style>
      :host{display:block;width:100%;min-width:0;color:#252c35;font:14px/1.5 Arial,sans-serif}*{box-sizing:border-box}p{margin:8px 0}.bar{display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin-bottom:12px}.badge{padding:4px 8px;background:#e7edf4;border-radius:4px;color:#142d4e;font-weight:600}.bad{background:#f7dfdc;color:#8c2924}.muted{color:#596572;font-size:12px}.notice{padding:10px 12px;background:#edf2fa;color:#142d4e}.error{padding:10px 12px;background:#fff0d4;color:#62400b}.error[hidden]{display:none}button{font:inherit;background:white;border:1px solid #aab2bc;border-radius:5px;padding:8px 12px;min-height:40px;cursor:pointer;color:#142d4e}button:hover{background:#eaf0fb}button:disabled{opacity:.6;cursor:wait}button:focus-visible{outline:3px solid #285bc5;outline-offset:3px}h3{font-size:16px;color:#142d4e;margin:20px 0 8px}table{width:100%;border-collapse:collapse;text-align:left}th,td{padding:10px 12px 10px 0;border-bottom:1px solid #dce0e5;vertical-align:top}th{font-size:12px;color:#596572}td{overflow-wrap:anywhere}code{font:12px/1.5 ui-monospace,Consolas,monospace}strong{color:#142d4e}.wrap{overflow-x:auto}.empty{color:#596572;padding:16px 0}.tables{max-width:100%;min-width:0}
      @media(max-width:650px){.wrap{overflow:visible}table,tbody,tr,td{display:block;width:100%}thead{display:none}tr{padding:10px 0;border-bottom:1px solid #ccd2d9}td{border:0;padding:5px 0}td:before{content:attr(data-label);display:block;color:#596572;font-size:12px}h3{margin-top:16px}}
      </style><div class="bar"><span id="state" class="badge" role="status">Conectando</span><span id="time" class="muted"></span><button id="refresh" type="button">Actualizar datos</button></div>
      <p id="error" class="error" role="status" hidden></p><p id="admission" class="notice"></p><div class="tables" id="content"></div>`;
    this.$=id=>this.shadowRoot.getElementById(id);
    this.shadowRoot.addEventListener("click",e=>e.stopPropagation());
    this.$("refresh").onclick=()=>this.poll();
    this.poll();this.timer=setInterval(()=>this.poll(),2000);
  }
  disconnectedCallback(){clearInterval(this.timer);}
  async get(path){
    const r=await fetch("/api"+path,{credentials:"same-origin",cache:"no-store",signal:AbortSignal.timeout(8000)});
    if(!r.ok)throw new Error(r.status===401||r.status===403?"La sesión ha caducado. Recarga e inicia sesión.":"No se pueden consultar los datos (HTTP "+r.status+").");
    return r.json();
  }
  async poll(){
    if(this.loading||!this.isConnected)return;
    const currentView=this.routeView();if(currentView!==this.view){this.view=currentView;this.signature=null;this.$("content").replaceChildren();}
    this.loading=true;this.$("refresh").disabled=true;
    const view=this.view;
    try{
      const overview=await this.get("/overview");let extra=null;
      if(view==="governance")extra=await this.get("/audit");
      if(view==="budgets")extra=await Promise.all((overview.budget_accounts||[]).map(a=>this.get("/budget/"+encodeURIComponent(a.account))));
      if(!this.isConnected||this.view!==view)return;
      this.render(overview,extra);
      this.$("state").textContent="Datos actualizados";this.$("state").className="badge";
      this.$("time").textContent="Última consulta "+new Date().toLocaleTimeString("es-ES")+" · cada 2 s";
      this.$("error").hidden=true;
    }catch(e){
      this.$("state").textContent="Sin actualización";this.$("state").className="badge bad";
      this.$("error").textContent=(e.name==="TimeoutError"?"La consulta ha superado 8 segundos.":e.message)+" Los datos anteriores pueden estar desactualizados. Se reintentará automáticamente.";
      this.$("error").hidden=false;
    }finally{this.loading=false;this.$("refresh").disabled=false;}
  }
  node(tag,text){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;}
  state(value){return({active:"Activo",idle:"Disponible",busy:"Ocupado",unknown:"Sin heartbeat",stale:"Heartbeat caducado",node_unavailable:"Nodo no disponible",disabled:"Deshabilitado",ready:"En cola",assigned:"Asignada",running:"En ejecución",review:"Pendiente de aceptación",cancelled:"Cancelada",available:"Disponible",paused:"Admisión pausada"})[value]||value||"Desconocido";}
  date(value){if(value==null)return"Sin registro";const d=new Date(typeof value==="number"?value*1000:value);return isNaN(d)?"Sin fecha válida":d.toLocaleString("es-ES");}
  yes(value){return Number(value)===1?"Sí":"No";}
  spec(t){try{return JSON.parse(t.spec)}catch{return{}}}
  table(headers,rows){
    if(!rows.length)return this.node("p","Todavía no hay registros en esta vista.");
    const wrap=this.node("div");wrap.className="wrap";const t=this.node("table"),head=this.node("thead"),hr=this.node("tr"),body=this.node("tbody");
    for(const h of headers)hr.append(this.node("th",h));head.append(hr);
    for(const row of rows){const tr=this.node("tr");row.forEach((v,i)=>{const td=this.node("td",v??"—");td.dataset.label=headers[i];tr.append(td);});body.append(tr);}
    t.append(head,body);wrap.append(t);return wrap;
  }
  reason(r){const [key,period]=r.split(":");return (({missing_active_window:"Falta una ventana vigente",missing_or_stale_usage:"Falta una lectura de consumo vigente",budget_exhausted:"Presupuesto agotado",insufficient_budget:"Saldo insuficiente",unreconciled_usage:"Consumo pendiente de conciliar",reservation_crossed_reset:"Una reserva cruzó el reinicio",human_pause:"Pausa del operador"})[key]||key)+(period?" ("+period+")":"");}
  money(value,mode){return mode==="subscription"?(Number(value)/100).toLocaleString("es-ES")+" %":new Intl.NumberFormat("es-ES",{style:"currency",currency:"USD",maximumFractionDigits:6}).format(Number(value)/1000000);}
  render(o,extra){
    this.$("admission").textContent=(o.paused?"Admisión pausada.":"Admisión habilitada, sujeta a presupuesto y capacidad.")+" El refresco de estos registros conserva los campos que estés editando.";
    const content=this.$("content"),nodes=o.nodes||[],workers=o.workers||[],tasks=o.tasks||[];
    let children=[];
    if(this.view==="nodes")children=[this.table(["Nodo","Rol","Heartbeat declarado","Último heartbeat","Habilitado","Workers registrados"],nodes.map(n=>[n.id,n.role,this.state(n.status),this.date(n.heartbeat),this.yes(n.enabled),workers.filter(w=>w.node===n.id).length]))];
    if(this.view==="workers")children=[this.table(["Boxer","Nodo","Estado declarado","Tarea asignada","Último heartbeat","Habilitado"],workers.map(w=>{const task=tasks.find(t=>t.worker===w.id&&["assigned","running"].includes(t.state));return[w.id,w.node,this.state(w.status),task?task.project+" / "+task.id+" · "+(this.spec(task).title||""):"Sin tarea activa asignada",this.date(w.heartbeat),this.yes(w.enabled)];}))];
    if(this.view==="tasks")children=[this.table(["Proyecto / tarea","Descripción","Estado","Boxer","Progreso declarado","Cancelación solicitada","Candidato"],tasks.map(t=>[t.project+" / "+t.id,this.spec(t).title||t.id,this.state(t.state),t.worker||"Sin asignar",t.progress==null||Number(t.progress)===0?"Sin estimación":t.progress+" %",this.yes(t.cancel_requested),t.candidate_sha||"Sin candidato"]))];
    if(this.view==="governance")children=[this.node("h3","Últimas acciones de gobierno"),this.table(["Fecha local","Operador","Acción","Recurso"],(extra||[]).map(e=>[this.date(e.at),e.actor,e.action,e.subject]))];
    if(this.view==="budgets"){
      if(!extra?.length)children=[this.node("p","No hay cuentas configuradas. Utiliza el formulario para registrar una política.")];
      for(const account of extra||[]){
        const record=(o.budget_accounts||[]).find(a=>a.account===account.account)||{};
        let shares={};try{shares=JSON.parse(record.shares)}catch{}
        children.push(this.node("h3",account.account+" · "+(account.mode==="subscription"?"Suscripción":"API en USD")),
          this.node("p",this.state(account.state)+(account.reasons?.length?": "+account.reasons.map(r=>this.reason(r)).join("; "):"")+"."),
          this.node("p","Política: "+Object.entries(shares).map(([k,v])=>k+" "+(v/100).toLocaleString("es-ES")+" %").join(" · ")),
          this.table(["Ventana","Tope permitido","Consumo contabilizado","Reservas","Disponible para reservar","Última lectura","Reinicio"],(account.windows||[]).map(w=>[w.period,this.money(w.cap,account.mode),this.money(w.used,account.mode),this.money(w.reserved,account.mode),this.money(w.remaining,account.mode),this.date(w.observed_at),this.date(w.end)])));
      }
      children.push(this.node("p","El saldo del registro es conservador y puede diferir de la última cuota observada del proveedor. Sin una observación vigente no se autoriza gasto."));
    }
    // Preserve text selection and table focus when only the polling timestamp changes.
    const signature=JSON.stringify([this.view,this.view==="governance"?extra:this.view==="budgets"?[o.budget_accounts,extra]:this.view==="nodes"?[nodes,workers.length]:this.view==="workers"?[workers,tasks]:tasks]);
    if(signature!==this.signature){this.signature=signature;content.replaceChildren(...children);}
  }
}
customElements.define("agentfarm-registry",AgentFarmRegistry);

