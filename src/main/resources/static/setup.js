const $=id=>document.getElementById(id);
async function api(path,body){
 const options={credentials:"same-origin",cache:"no-store",signal:AbortSignal.timeout(10000)};
 if(body!==undefined){const csrf=await api("/csrf");options.method="POST";options.headers={"Content-Type":"application/json",[csrf.header]:csrf.token};options.body=JSON.stringify(body);}
 const r=await fetch("/api"+path,options);if(!r.ok)throw Error("Operación rechazada (HTTP "+r.status+"). Revisa la sesión y la configuración.");
 return r.json();
}
function status(text){$("feedback").textContent=text;}
function option(select,value){const o=document.createElement("option");o.value=value;o.textContent=value;select.append(o);}
function hideSecret(){$("token").value="";$("secret").hidden=true;}
async function credentials(){
 const items=await api("/executors");$("credentials").replaceChildren();
 for(const item of items){
  const li=document.createElement("li");li.textContent=item.node+" · "+item.account+" · "+(item.revoked?"revocada":"activa")+" ";
  if(!item.revoked){const b=document.createElement("button");b.type="button";b.textContent="Revocar";b.onclick=async()=>{b.disabled=true;try{await api("/executors/"+encodeURIComponent(item.id)+"/revoke",{});hideSecret();await credentials();status("Credencial revocada. El ejecutor dejará de autenticarse.");}catch(e){status(e.message);b.disabled=false;}};li.append(b);}
  $("credentials").append(li);
 }
 if(!items.length)$("credentials").textContent="Ninguna credencial registrada.";
}
async function check(){
 const [runtime,installation]=await Promise.all([api("/runtime"),api("/installation")]);
 const own=runtime.fresh&&runtime.snapshot?.node===$("node").value;
 $("runtime").textContent=own?"Nodo conectado · "+runtime.snapshot.node+" · "+runtime.snapshot.phase:"Sin señal reciente del nodo seleccionado.";
 $("quota").textContent=own&&runtime.quota_fresh?"Cuota reciente observada por el ejecutor. Revisa el margen autorizado en Clover.":"Cuota actual desconocida para este nodo. No se admite trabajo nuevo.";
 $("publisher").textContent=installation.integration_publisher?"Publicador de integración configurado en el panel. Cada fusión requiere aprobación humana.":"Integración no configurada: falta el archivo privado AGENTFARM_GITHUB_TOKEN_FILE del panel.";
}
$("enroll").onsubmit=async e=>{
 e.preventDefault();$("create").disabled=true;hideSecret();
 try{const result=await api("/executors",{node:$("node").value,account:$("account").value});$("token").value=result.token;$("secret").hidden=false;await credentials();status("Credencial creada. Descárgala antes de salir; no se almacena en el navegador.");}
 catch(e){status(e.message);}finally{$("create").disabled=false;}
};
$("download").onclick=()=>{const token=$("token").value;if(!token)return;const url=URL.createObjectURL(new Blob([token+"\n"],{type:"application/octet-stream"}));const a=document.createElement("a");a.href=url;a.download="node-token";a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
$("hide").onclick=hideSecret;
$("check").onclick=async()=>{try{await check();status("Comprobación actualizada.");}catch(e){status(e.message);}};
window.addEventListener("pagehide",hideSecret);
try{
 const overview=await api("/overview");
 for(const node of overview.nodes)option($("node"),node.id);
 for(const account of overview.budget_accounts)option($("account"),account.account);
 $("create").disabled=!$("node").value||!$("account").value;
 await Promise.all([credentials(),check()]);status("Selecciona el nodo y su cuenta.");
}catch(e){status(e.message);}
