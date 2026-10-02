package dimblend.radio.client;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import dimblend.radio.acoustics.SteamRenderer;
import org.junit.jupiter.api.Test;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import static org.lwjgl.openal.AL10.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real device-clock regression: repeated refills after a scheduling stall must not keep restarting. */
class RadioOpenALRecoveryTest {
    @Test void learnedHeadroomBreaksTheRepeatedStopPlayCycle() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        long device=ALC10.alcOpenDevice((ByteBuffer)null);
        assertNotEquals(0,device);
        long context=0;
        try {
            var caps=ALC.createCapabilities(device);
            context=ALC10.alcCreateContext(device,(IntBuffer)null);
            assertNotEquals(0,context);
            assertTrue(ALC10.alcMakeContextCurrent(context));
            AL.createCapabilities(caps);
            int shallow=playWithStalls(false), adaptive=playWithStalls(true);
            System.out.println("[OpenAL] shallow restarts="+shallow+", adaptive restarts="+adaptive);
            assertTrue(shallow>=3,"Control must reproduce repeated starvation");
            assertTrue(adaptive<shallow,"Measured underruns must add enough headroom to prevent repeated stop/play");
            assertEquals(AL_NO_ERROR,alGetError());
        } finally {
            ALC10.alcMakeContextCurrent(0);
            if(context!=0)ALC10.alcDestroyContext(context);
            ALC10.alcCloseDevice(device);
        }
    }

    private static int playWithStalls(boolean adaptive) throws InterruptedException {
        int source=alGenSources(), restarts=0;
        var policy=new RadioStreamBuffering.State(48000);
        try {
            alSourcef(source,AL_GAIN,0); // silent test; the device clock still consumes the PCM buffers
            fill(source,adaptive?policy.target():2);
            alSourcePlay(source);
            for(int tick=0;tick<10;tick++) {
                Thread.sleep(tick==0?65:35);
                boolean stopped=alGetSourcei(source,AL_SOURCE_STATE)==AL_STOPPED;
                int processed=alGetSourcei(source,AL_BUFFERS_PROCESSED);
                for(int i=0;i<processed;i++)alDeleteBuffers(alSourceUnqueueBuffers(source));
                fill(source,processed);
                if(stopped) {
                    restarts++;
                    if(adaptive)fill(source,Math.max(0,policy.underrun()-alGetSourcei(source,AL_BUFFERS_QUEUED)));
                    alSourcePlay(source);
                }
            }
            return restarts;
        } finally {
            alSourceStop(source);
            int queued=alGetSourcei(source,AL_BUFFERS_QUEUED);
            for(int i=0;i<queued;i++)alDeleteBuffers(alSourceUnqueueBuffers(source));
            alDeleteSources(source);
        }
    }

    private static void fill(int source,int count) {
        for(int i=0;i<count;i++) {
            int buffer=alGenBuffers();
            alBufferData(buffer,AL_FORMAT_MONO16,ByteBuffer.allocateDirect(SteamRenderer.FRAME*2),48000);
            alSourceQueueBuffers(source,buffer);
        }
    }
}
