// Offscreen alarm playback for Chrome 109+. HTMLAudioElement is the primary engine.
let player=null, timer=null, busy=false, alarmGeneration=0;
let soundSettings={soundEnabled:true,volume:85};
function wavData(){
 const sampleRate=22050, duration=.40, count=Math.floor(sampleRate*duration);
 const bytes=new Uint8Array(44+count*2);const view=new DataView(bytes.buffer);
 function str(pos,s){for(let i=0;i<s.length;i++)bytes[pos+i]=s.charCodeAt(i);}
 str(0,'RIFF');view.setUint32(4,36+count*2,true);str(8,'WAVE');str(12,'fmt ');
 view.setUint32(16,16,true);view.setUint16(20,1,true);view.setUint16(22,1,true);
 view.setUint32(24,sampleRate,true);view.setUint32(28,sampleRate*2,true);
 view.setUint16(32,2,true);view.setUint16(34,16,true);str(36,'data');view.setUint32(40,count*2,true);
 for(let i=0;i<count;i++){const t=i/sampleRate,fade=Math.min(1,t/.025,(duration-t)/.055);const signal=(Math.sin(2*Math.PI*880*t)+.35*Math.sin(2*Math.PI*1320*t))/1.35;view.setInt16(44+i*2,Math.round(signal*Math.max(0,fade)*25000),true);}
 return URL.createObjectURL(new Blob([bytes],{type:'audio/wav'}));
}
const soundUrl=wavData();
async function play(){
 const d=soundSettings;
 if(!d.soundEnabled)throw Error('Sound is disabled in Alerts settings');
 if(!player){player=new Audio(soundUrl);player.preload='auto';}
 player.volume=Math.min(1,Math.max(0,Number(d.volume)/100));
 if(player.volume===0)throw Error('Alarm volume is zero');
 player.pause();player.currentTime=0;
 await player.play(); // rejects when Chrome blocks playback: report rather than silently succeed
 return true;
}
function stop(){alarmGeneration++;if(timer){clearInterval(timer);timer=null;}if(player){player.pause();player.currentTime=0;}}
async function start(){
 stop();const myGeneration=alarmGeneration;
 await play();
 // STOP may arrive while player.play() is still resolving.
 if(myGeneration!==alarmGeneration){if(player){player.pause();player.currentTime=0;}return;}
 timer=setInterval(()=>{if(myGeneration===alarmGeneration)play().catch(e=>console.warn('Alarm playback:',e));},1300);
}
chrome.runtime.onMessage.addListener((msg,sender,reply)=>{
 if(msg?.type!=='OFFSCREEN_AUDIO')return;
 if(msg.settings) soundSettings={...soundSettings,...msg.settings};
 (async()=>{
  if(msg.action==='ARM'||msg.action==='TEST')await play();
  else if(msg.action==='START')await start();
  else if(msg.action==='STOP')stop();
  else if(msg.action==='UPDATE'){
    if(!soundSettings.soundEnabled || Number(soundSettings.volume)<=0)stop();
    else if(!timer)await start();
    else if(player)player.volume=Math.min(1,Math.max(0,Number(soundSettings.volume)/100));
  }
  else throw Error('Unknown audio command');
  return {ok:true};
 })().then(reply).catch(e=>reply({ok:false,error:String(e.message||e)}));
 return true;
});
// State and restart commands are managed by background.js.
