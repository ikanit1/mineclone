package com.mineclone;

import com.mineclone.audio.*;
import java.util.List;

final class RainAudioTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("rain starts on the first frame and maintains the loop without timer gaps", RainAudioTests::continuous);
        r.run("rain fades under cover underwater and after clearing then releases its source", RainAudioTests::fades);
        r.run("rain fades consistently across frame rates and resets between worlds", RainAudioTests::timing);
    }
    private static final class Audio extends SoundEngine {
        int updates;
        boolean playing;
        @Override public void updateLoopOneOf(String key,List<String> paths,float volume,float pitch) {
            check(key.equals(RainAmbience.LOOP_KEY),"dedicated rain source");
            check(volume>0 && volume<=.55f,"bounded gain");
            playing=true;updates++;
        }
        @Override public void stopLoop(String key) { playing=false; }
    }
    private static RainAmbience rain(Audio audio) { return new RainAmbience(audio,List.of("rain.ogg")); }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static void continuous() {
        Audio audio=new Audio();var rain=rain(audio);
        rain.update(1f/60,.1f,15,false);
        check(audio.playing,"visible drizzle must not wait 7-13 seconds");
        for(int i=0;i<1800;i++)rain.update(1f/60,.8f,15,false);
        check(audio.updates==1801,"keep source alive every frame and retry asynchronous loading");
        float full=rain.gain();
        for(int i=0;i<120;i++)rain.update(1f/60,.2f,15,false);
        check(rain.gain()<full && rain.gain()>.05f,"light rain stays audible but quieter");
    }
    private static void fades() {
        for(int state=0;state<3;state++) {
            Audio audio=new Audio();var rain=rain(audio);
            for(int i=0;i<120;i++)rain.update(1f/60,1,15,false);
            float before=rain.gain();
            float intensity=state==0?0:1;int sky=state==1?0:15;boolean water=state==2;
            rain.update(1f/60,intensity,sky,water);
            check(rain.gain()>0 && rain.gain()<before,"smooth fade rather than hard cut");
            for(int i=0;i<180;i++)rain.update(1f/60,intensity,sky,water);
            check(!audio.playing && rain.gain()==0,"source released when inaudible");
            rain.update(1f/60,1,15,false);check(audio.playing,"rain returns immediately on exposure");
        }
    }
    private static void timing() {
        var slow=rain(new Audio());var fast=rain(new Audio());
        for(int i=0;i<20;i++)slow.update(.05f,.8f,12,false);
        for(int i=0;i<120;i++)fast.update(1f/120,.8f,12,false);
        check(Math.abs(slow.gain()-fast.gain())<1e-5,"frame-rate independent gain");
        slow.reset();slow.update(.05f,0,15,false);check(slow.gain()==0,"no rain carried into the next world");
    }
}
