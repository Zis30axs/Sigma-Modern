package com.mojang.blaze3d.audio;

import com.viaversion.viaaprilfools.api.AprilFoolsProtocolVersion;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import java.nio.ByteBuffer;
import java.util.OptionalInt;
import javax.sound.sampled.AudioFormat;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.lwjgl.openal.AL10;

@OnlyIn(Dist.CLIENT)
public class SoundBuffer {
    private @Nullable ByteBuffer data;
    private final AudioFormat format;
    private boolean hasAlBuffer;
    private int alBuffer;
    private final int size;

    public SoundBuffer(final ByteBuffer data, final AudioFormat format) {
        this.data = data;
        this.format = format;
        this.size = data.limit();
        // MODIFIED for porting: was VFP april_fools_8bit_sound MixinSoundBuffer#modifyBuffer (@Inject <init> RETURN)
        // Only the s3d_shareware April Fools snapshot played 8 bit audio; FeaturesLoading reloads the sound manager
        // whenever the target version crosses it, so every buffer is rebuilt through this constructor.
        if (ProtocolTranslator.getTargetVersion().equals(AprilFoolsProtocolVersion.s3d_shareware)) {
            this.vfpApply8BitSound(data);
        }
    }

    // MODIFIED for porting: was VFP april_fools_8bit_sound MixinSoundBuffer#viaFabricPlus$apply8BitSound (@Unique)
    private void vfpApply8BitSound(final ByteBuffer byteBuffer) {
        if (this.format.getChannels() == 1) {
            this.vfpApply8BitMono(byteBuffer);
        } else {
            this.vfpApply8BitStereo(byteBuffer);
        }
    }

    // MODIFIED for porting: was VFP april_fools_8bit_sound MixinSoundBuffer#viaFabricPlus$apply8BitMono (@Unique)
    private void vfpApply8BitMono(final ByteBuffer byteBuffer) {
        short sample = 0;
        int held = 0;

        while (byteBuffer.hasRemaining()) {
            if (held == 0) {
                byteBuffer.mark();
                sample = (short)(byteBuffer.getShort() & 0xFFFFFFFC);
                byteBuffer.reset();
                held = 15;
            } else {
                --held;
            }

            byteBuffer.putShort(sample);
        }

        byteBuffer.flip();
    }

    // MODIFIED for porting: was VFP april_fools_8bit_sound MixinSoundBuffer#viaFabricPlus$apply8BitStereo (@Unique)
    private void vfpApply8BitStereo(final ByteBuffer byteBuffer) {
        short leftSample = 0;
        short rightSample = 0;
        int held = 0;

        while (byteBuffer.hasRemaining()) {
            if (held == 0) {
                byteBuffer.mark();
                leftSample = (short)(byteBuffer.getShort() & 0xFFFFFFFC);
                rightSample = (short)(byteBuffer.getShort() & 0xFFFFFFFC);
                byteBuffer.reset();
                held = 15;
            } else {
                --held;
            }

            byteBuffer.putShort(leftSample);
            byteBuffer.putShort(rightSample);
        }

        byteBuffer.flip();
    }

    OptionalInt getAlBuffer() {
        if (!this.hasAlBuffer) {
            if (this.data == null) {
                return OptionalInt.empty();
            }

            int audioFormat = OpenAlUtil.audioFormatToOpenAl(this.format);
            int[] intBuffer = new int[1];
            AL10.alGenBuffers(intBuffer);
            if (OpenAlUtil.checkALError("Creating buffer")) {
                return OptionalInt.empty();
            }

            AL10.alBufferData(intBuffer[0], audioFormat, this.data, (int)this.format.getSampleRate());
            if (OpenAlUtil.checkALError("Assigning buffer data")) {
                return OptionalInt.empty();
            }

            this.alBuffer = intBuffer[0];
            this.hasAlBuffer = true;
            this.data = null;
        }

        return OptionalInt.of(this.alBuffer);
    }

    public void discardAlBuffer() {
        if (this.hasAlBuffer) {
            AL10.alDeleteBuffers(new int[]{this.alBuffer});
            if (OpenAlUtil.checkALError("Deleting stream buffers")) {
                return;
            }
        }

        this.hasAlBuffer = false;
    }

    public OptionalInt releaseAlBuffer() {
        OptionalInt result = this.getAlBuffer();
        this.hasAlBuffer = false;
        return result;
    }

    public AudioFormat format() {
        return this.format;
    }

    public int size() {
        return this.size;
    }

    public boolean isValid() {
        return this.data != null || this.hasAlBuffer;
    }
}