import com.mineclone.audio.*;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.*;
import java.util.Map;

/** Real Vorbis decoding and OpenAL playback checks for the continuous rain bed. */
public final class RainAudioSmoke {
    private static int source(SoundEngine sound) throws Exception {
        var field=SoundEngine.class.getDeclaredField("loopingSources");field.setAccessible(true);
        Object loop=((Map<?,?>)field.get(sound)).get(RainAmbience.LOOP_KEY);
        if(loop==null)return -1;
        var id=loop.getClass().getDeclaredField("source");id.setAccessible(true);return id.getInt(loop);
    }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        var sounds=new Sounds();var samples=sounds.ambientRain();check(samples.size()==8,"eight bundled rain recordings");
        for(String path:samples)try(var stack=MemoryStack.stackPush()) {
            var channels=stack.mallocInt(1);var rate=stack.mallocInt(1);
            var pcm=STBVorbis.stb_vorbis_decode_filename(path,channels,rate);
            check(pcm!=null && pcm.remaining()>0,"rain decodes: "+path);
            double energy=0;for(int i=0;i<pcm.limit();i++)energy+=(double)pcm.get(i)*pcm.get(i);
            check(Math.sqrt(energy/pcm.limit())/32768>.01,"recording contains audible signal");
            MemoryUtil.memFree(pcm);
        }
        SoundEngine sound=new SoundEngine();sound.init();sound.setMasterVolume(.2f);
        var rain=new RainAmbience(sound,samples);
        try {
            // Deliberately cold: an unloaded sample must be retried next frame, not in 13 seconds.
            long deadline=System.nanoTime()+3_000_000_000L;
            while(source(sound)<0 && System.nanoTime()<deadline) {
                rain.update(1f/60,.8f,15,false);sound.tick();Thread.sleep(10);
            }
            int id=source(sound);check(id>=0,"rain starts after asynchronous decode");
            int buffer=AL10.alGetSourcei(id,AL10.AL_BUFFER);
            check(AL10.alGetSourcei(id,AL10.AL_LOOPING)==AL10.AL_TRUE,"loop enabled");
            check(AL10.alGetSourcei(id,AL10.AL_SOURCE_RELATIVE)==AL10.AL_TRUE,"rain follows listener");
            float offset=-1;boolean wrapped=false;
            for(int frame=0;frame<190;frame++) {
                rain.update(1f/60,.8f,15,false);sound.tick();Thread.sleep(16);
                check(source(sound)==id && AL10.alGetSourcei(id,AL10.AL_BUFFER)==buffer,"same source and clip maintained");
                check(AL10.alGetSourcei(id,AL10.AL_SOURCE_STATE)==AL10.AL_PLAYING,"no silent interval");
                float current=AL10.alGetSourcef(id,org.lwjgl.openal.AL11.AL_SEC_OFFSET);if(current<offset)wrapped=true;offset=current;
            }
            check(wrapped,"playback crossed the end of the recording without stopping");
            sound.setEffectsVolume(0);check(AL10.alGetSourcef(id,AL10.AL_GAIN)==0,"effects slider mutes while paused");
            sound.setEffectsVolume(1);check(AL10.alGetSourcef(id,AL10.AL_GAIN)>0,"effects slider restores rain");
            sound.setMasterVolume(0);check(AL10.alGetSourcef(id,AL10.AL_GAIN)==0,"master slider mutes while paused");
            sound.setMasterVolume(.2f);
            for(int i=0;i<60;i++)rain.update(.05f,1,15,true);
            check(source(sound)<0,"underwater fades out and releases rain");
            rain.update(.05f,1,15,false);check(source(sound)>=0,"emerging starts the cached loop");
            for(int i=0;i<60;i++)rain.update(.05f,0,15,false);
            check(source(sound)<0,"clear weather stops rain");
            rain.update(.05f,1,15,false);rain.reset();check(source(sound)<0,"leaving world stops rain");
            check(AL10.alGetError()==AL10.AL_NO_ERROR,"no OpenAL errors");
            System.out.println("PASS: 8 Vorbis assets, cold decode, continuous OpenAL loop through sample boundary, volume sliders, underwater/clear/reset; no AL errors");
        } finally {sound.destroy();}
    }
}

