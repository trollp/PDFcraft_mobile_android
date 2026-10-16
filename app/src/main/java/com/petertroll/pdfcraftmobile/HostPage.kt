package com.petertroll.pdfcraftmobile

object HostPage {
    fun html(token: String): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<style>html,body,iframe{margin:0;border:0;width:100%;height:100%;overflow:hidden;background:#202124}</style></head>
<body><iframe id="app" allow="fullscreen" src="/$token/pdfcraft/index.html?host=parent&amp;webgl"></iframe>
<script>
'use strict';
const TOKEN=${jsString(token)}; const frame=document.getElementById('app');
function event(data){ fetch('/'+TOKEN+'/event',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)}).catch(()=>{}); }
window.addEventListener('message', async (e)=>{
 if(e.origin!==location.origin || e.source!==frame.contentWindow || !e.data) return;
 const m=e.data;
 if(m.type==='pdfcraft:ready'){
   event({type:m.type,version:m.version||''});
   try { const r=await fetch('/'+TOKEN+'/current.pdf',{cache:'no-store'}); if(r.status===200){const b=await r.arrayBuffer(); frame.contentWindow.postMessage({type:'pdfcraft:open',name:r.headers.get('X-Document-Name')||'document.pdf',bytes:b},location.origin,[b]);} } catch(err){event({type:'pdfcraft:failed',error:String(err)});}
 } else if(m.type==='pdfcraft:dirty'){event({type:m.type,dirty:!!m.dirty});}
 else if(m.type==='pdfcraft:failed'){event({type:m.type,error:String(m.error||'PdfCraft failed')});}
 else if(m.type==='pdfcraft:save' && m.bytes){
   fetch('/'+TOKEN+'/save',{method:'POST',headers:{'Content-Type':'application/pdf','X-Document-Name':encodeURIComponent(m.name||'document.pdf'),'X-Save-As':m.saveAs?'true':'false'},body:m.bytes}).then(r=>{if(!r.ok) throw Error('Save bridge '+r.status)}).catch(err=>event({type:'pdfcraft:failed',error:String(err)}));
 }
});
</script></body></html>"""

    private fun jsString(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
