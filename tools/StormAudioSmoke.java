import com.mineclone.audio.*;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.util.Map;

/** Real bundled wind decoding, OpenAL playback, gain controls and weather lifecycle. */
public final class StormAudioSmoke {
    static int source(SoundEngine engine) throws Exception {
        var field = SoundEngine.class.getDeclaredField("loopingSources"); field.setAccessible(true);
        Object loop = ((Map<?, ?>) field.get(engine)).get(StormAmbience.LOOP_KEY);
        if (loop == null) return -1;
        var id = loop.getClass().getDeclaredField("source"); id.setAccessible(true); return id.getInt(loop);
    }
    public static void main(String[] args) throws Exception {
        var samples = new Sounds().ambientWind();
        check(!samples.isEmpty(), "bundled wind clips exist");
        for (String path : samples) try (var stack = MemoryStack.stackPush()) {
            var pcm = STBVorbis.stb_vorbis_decode_filename(path, stack.mallocInt(1), stack.mallocInt(1));
            check(pcm != null && pcm.remaining() > 0, "wind decodes: " + path);
            double energy = 0;
            for (int i = 0; i < pcm.limit(); i++) energy += (double) pcm.get(i) * pcm.get(i);
            check(energy > pcm.limit() * 100.0, "wind contains audible signal");
            MemoryUtil.memFree(pcm);
        }
        var engine = new SoundEngine(); engine.init(); engine.setMasterVolume(.12f);
        var wind = new StormAmbience(engine, samples);
        try {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (source(engine) < 0 && System.nanoTime() < deadline) {
                wind.update(1f / 60, 1, 1, 6, 15, true, false); engine.tick(); Thread.sleep(10);
            }
            int id = source(engine); check(id >= 0, "cold asynchronous load starts wind");
            int buffer = AL10.alGetSourcei(id, AL10.AL_BUFFER);
            check(AL10.alGetSourcei(id, AL10.AL_LOOPING) == AL10.AL_TRUE, "wind uses looping source");
            for (int i = 0; i < 60; i++) {
                wind.update(1f / 60, 1, 1, 4 + (float) Math.sin(i * .1), 15, true, false);
                engine.tick(); Thread.sleep(10);
                check(source(engine) == id && AL10.alGetSourcei(id, AL10.AL_BUFFER) == buffer, "gusts do not restart sound");
                check(AL10.alGetSourcei(id, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING, "wind remains playing");
            }
            engine.setEffectsVolume(0); check(AL10.alGetSourcef(id, AL10.AL_GAIN) == 0, "effects slider mutes wind");
            engine.setEffectsVolume(1); check(AL10.alGetSourcef(id, AL10.AL_GAIN) > 0, "effects slider restores wind");
            for (int i = 0; i < 180; i++) wind.update(1f / 60, 1, 1, 6, 15, true, true);
            check(source(engine) < 0, "underwater releases source");
            wind.update(.05f, 1, 1, 6, 15, true, false); check(source(engine) >= 0, "return outdoors resumes cached wind");
            for (int i = 0; i < 180; i++) wind.update(1f / 60, 0, 0, 0, 15, true, false);
            check(source(engine) < 0, "clear weather releases source");
            wind.update(.05f, 1, 0, 6, 15, true, false); wind.reset();
            check(source(engine) < 0, "world exit stops storm wind");
            check(AL10.alGetError() == AL10.AL_NO_ERROR, "OpenAL has no errors");
            System.out.println("PASS: " + samples.size() + " Vorbis wind clips, continuous gusting OpenAL loop, volume slider, underwater/clear/reset");
        } finally { engine.destroy(); }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
