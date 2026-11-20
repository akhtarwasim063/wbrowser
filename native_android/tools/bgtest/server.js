// Tiny test server for the background-playback experiment. Serves a page with an <audio> element and prints what the page reports.
const http = require('http');
const sr = 8000, secs = 120;
// near-silent 8-bit mono WAV so the test does not make noise on the phone
const n = sr * secs, wav = Buffer.alloc(44 + n);
wav.write('RIFF', 0); wav.writeUInt32LE(36 + n, 4); wav.write('WAVEfmt ', 8); wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
wav.writeUInt32LE(sr, 24); wav.writeUInt32LE(sr, 28); wav.writeUInt16LE(1, 32); wav.writeUInt16LE(8, 34); wav.write('data', 36); wav.writeUInt32LE(n, 40);
for (let i = 0; i < n; i++) wav[44 + i] = 128 + Math.round(Math.sin(i / 8) * 1);
const page = `<!doctype html><meta name=viewport content="width=device-width"><title>bg test</title>
<body style="font:20px sans-serif;background:#123;color:#fff"><h2>Background play test</h2>
<audio id=a src="/a.wav" loop controls style="width:100%"></audio><pre id=o></pre>
<script>
var a=document.getElementById('a'),o=document.getElementById('o');
function log(m){ try{ fetch('/log',{method:'POST',body:m,keepalive:true}); }catch(e){} o.textContent=m+'\\n'+o.textContent.slice(0,400); }
['play','pause','playing','ended','stalled','waiting'].forEach(function(t){ a.addEventListener(t,function(){ log('audio '+t+' hidden='+document.hidden); }); });
['visibilitychange','blur','focus','pagehide','pageshow','freeze','resume'].forEach(function(t){ window.addEventListener(t,function(){ log('window '+t); },true); document.addEventListener(t,function(){ log('doc '+t); },true); });
setInterval(function(){ log('tick hidden='+document.hidden+' vs='+document.visibilityState+' focus='+document.hasFocus()+' paused='+a.paused+' t='+a.currentTime.toFixed(1)); },1000);
</script>`;
http.createServer((req, res) => {
  if (req.method === 'POST' && req.url === '/log') { let b = ''; req.on('data', (c) => b += c); req.on('end', () => { console.log(new Date().toISOString().slice(11, 23), b); res.end('ok'); }); return; }
  if (req.url === '/a.wav') { res.writeHead(200, { 'content-type': 'audio/wav', 'content-length': wav.length, 'accept-ranges': 'none' }); return res.end(wav); }
  res.writeHead(200, { 'content-type': 'text/html' }); res.end(page);
}).listen(8765, () => console.log('up'));
