"use strict";
let snapshot = null;
const $ = id => document.getElementById(id);
const labels = {PENDING:"待审核",PUBLISHED:"已发布",DISABLED:"已停用",REMOVED:"已移除"};
function notice(message, failed=false) { $("notice").textContent=message; $("notice").classList.toggle("error",failed); }
async function api(path,method="GET",body) {
  const token=$("token").value.trim();
  if(!token) throw new Error("请先输入管理员凭据");
  const result=await fetch(path,{method,headers:{Authorization:"Bearer "+token,"Content-Type":"application/json"},body:body===undefined?undefined:JSON.stringify(body),cache:"no-store",credentials:"omit"});
  const data=await result.json();
  if(!result.ok) throw new Error("操作未完成："+(data.error||result.status));
  return data;
}
async function perform(work,message) {
  try { await work(); snapshot=await api("/api/snapshot"); render(); notice(message); }
  catch(error) { notice(error.message,true); }
}
function cell(value) { const td=document.createElement("td");if(value instanceof Node)td.append(value);else td.textContent=value==null?"—":String(value);return td; }
function button(label,action,className="") { const b=document.createElement("button");b.type="button";b.textContent=label;b.className=className;b.onclick=action;return b; }
function table(target,headers,rows) {
  const wrap=document.createElement("div");wrap.className="table-scroll";
  const t=document.createElement("table"),head=document.createElement("tr");
  headers.forEach(text=>{const th=document.createElement("th");th.textContent=text;head.append(th);});
  const thead=document.createElement("thead");thead.append(head);t.append(thead);
  const body=document.createElement("tbody");rows.forEach(values=>{const row=document.createElement("tr");values.forEach(v=>row.append(cell(v)));body.append(row);});t.append(body);wrap.append(t);$(target).replaceChildren(wrap);
}
function option(select,value,label) {const o=document.createElement("option");o.value=value;o.textContent=label;select.append(o);}
function render() {
  $("version").textContent="配置版本 "+snapshot.version;
  const tools=Object.values(snapshot.tools), groups=Object.values(snapshot.groups);
  table("service-list",["服务","组织","MCP 地址","状态"],Object.values(snapshot.services).map(s=>[s.id,s.tenant,s.endpoint,s.enabled?"已启用":"已关闭"]));
  table("tool-list",["工具 / 来源","修订","状态","资源权限校验","操作"],tools.map(t=>{
    const protect=document.createElement("input");protect.type="checkbox";protect.checked=t.resourceProtected;protect.setAttribute("aria-label","对 "+t.upstreamName+" 启用资源权限校验");
    const actions=document.createElement("div");
    if(t.state!=="REMOVED"){actions.append(button("发布",()=>perform(()=>api("/api/tools/"+encodeURIComponent(t.id)+"/publish","POST",{resourceProtected:protect.checked}),"已发布；请检查当前修订的授权。")));actions.append(button("停用",()=>perform(()=>api("/api/tools/"+encodeURIComponent(t.id)+"/disable","POST",{}),"工具已停用，网关将在配置同步后拒绝调用。")));}
    const state=document.createElement("span");state.className="state";state.textContent=labels[t.state]||t.state;
    return [button(t.upstreamName+" / "+t.serviceId,()=>{$("definition").textContent=JSON.stringify(t,null,2);$("definition").parentElement.open=true;},"link"),t.revision,state,protect,actions];
  }));
  $("group-tools").replaceChildren();
  tools.filter(t=>t.state!=="REMOVED").forEach(t=>{
    const row=document.createElement("div");row.className="binding";
    const check=document.createElement("input");check.type="checkbox";check.dataset.toolId=t.id;check.id="binding-"+t.id;
    const label=document.createElement("label");label.htmlFor=check.id;label.textContent=t.upstreamName+" · "+t.serviceId+" · "+t.tenant;
    const alias=document.createElement("input");alias.type="text";alias.value=t.upstreamName.replace(/[^A-Za-z0-9_.-]/g,"_");alias.placeholder="对外工具名称";alias.setAttribute("aria-label",t.upstreamName+" 的对外名称");
    row.append(check,label,alias);$("group-tools").append(row);
  });
  table("group-list",["分组","组织","统一名称","工具数","操作"],groups.map(g=>[g.id,g.tenant,g.title,Object.keys(g.tools).length,button("编辑",()=>{
    const form=$("group-form");["id","tenant","title","description"].forEach(k=>form.elements[k].value=g[k]||"");
    document.querySelectorAll(".binding").forEach(row=>{const check=row.querySelector("[type=checkbox]");const binding=Object.entries(g.tools).find(([,id])=>id===check.dataset.toolId);check.checked=!!binding;if(binding)row.querySelector("[type=text]").value=binding[0];});
    form.scrollIntoView({behavior:"smooth"});
  })]));
  $("grant-group").replaceChildren();option($("grant-group"),"","所有引用此工具的组");
  groups.forEach(g=>option($("grant-group"),g.id,g.title+" · "+g.tenant));
  $("grant-tool").replaceChildren();tools.filter(t=>t.state==="PUBLISHED").forEach(t=>option($("grant-tool"),t.id,t.upstreamName+" · "+t.serviceId+" · 修订 "+t.revision));
  table("grant-list",["对象","组织 / 来源","范围","工具 / 修订","操作"],Object.values(snapshot.grants).map(g=>[
    g.subjectType+" · "+g.subject,g.tenant+" / "+g.issuer,g.groupId||"所有引用组",
    (snapshot.tools[g.toolId]?.upstreamName||g.toolId)+" / "+g.toolRevision,
    button("撤销",()=>perform(()=>api("/api/grants/"+encodeURIComponent(g.id),"DELETE"),"授权已撤销，将在配置同步后生效。"))
  ]));
}
$("connect").onclick=()=>perform(async()=>{},"目录已刷新。");
$("service-form").onsubmit=event=>{event.preventDefault();const data=Object.fromEntries(new FormData(event.target));data.enabled=event.target.elements.enabled.checked;data.transport="streamable-http";data.credentialRef=data.credentialRef||null;perform(()=>api("/api/services","POST",data),"服务已保存，等待发现服务同步。");};
$("group-form").onsubmit=event=>{event.preventDefault();const data=Object.fromEntries(new FormData(event.target));data.tools={};let duplicate=false;document.querySelectorAll(".binding").forEach(row=>{const check=row.querySelector("[type=checkbox]");if(check.checked){const alias=row.querySelector("[type=text]").value.trim();if(Object.hasOwn(data.tools,alias))duplicate=true;data.tools[alias]=check.dataset.toolId;}});if(duplicate){notice("同组的对外名称不能重复",true);return;}perform(()=>api("/api/groups","POST",data),"能力组已保存；加入分组不会自动授予权限。");};
$("grant-form").onsubmit=event=>{event.preventDefault();if(!snapshot)return;const data=Object.fromEntries(new FormData(event.target));data.groupId=data.groupId||null;const tool=snapshot.tools[data.toolId];if(!tool){notice("请选择已发布工具",true);return;}data.toolRevision=tool.revision;perform(()=>api("/api/grants","POST",data),"已保存当前工具修订的授权。");};
