package javax.sound.sampled;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Android'da javax.sound.sampled yoʻq. jump3r kutubxonasining LameEncoder sinfi
 * AudioFormat'ni talab qiladi, shuning uchun minimal mos "qolip" shu yerda.
 */
public class AudioFormat {

    public static class Encoding {
        public static final Encoding PCM_SIGNED = new Encoding("PCM_SIGNED");
        public static final Encoding PCM_UNSIGNED = new Encoding("PCM_UNSIGNED");
        public static final Encoding PCM_FLOAT = new Encoding("PCM_FLOAT");

        private final String name;

        public Encoding(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Encoding && name.equals(((Encoding) o).name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    protected Encoding encoding;
    protected float sampleRate;
    protected int sampleSizeInBits;
    protected int channels;
    protected int frameSize;
    protected float frameRate;
    protected boolean bigEndian;
    private Map<String, Object> properties;

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits, int channels,
                       int frameSize, float frameRate, boolean bigEndian) {
        this.encoding = encoding;
        this.sampleRate = sampleRate;
        this.sampleSizeInBits = sampleSizeInBits;
        this.channels = channels;
        this.frameSize = frameSize;
        this.frameRate = frameRate;
        this.bigEndian = bigEndian;
        this.properties = new HashMap<String, Object>();
    }

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits, int channels,
                       int frameSize, float frameRate, boolean bigEndian, Map<String, Object> properties) {
        this(encoding, sampleRate, sampleSizeInBits, channels, frameSize, frameRate, bigEndian);
        this.properties = new HashMap<String, Object>(properties);
    }

    public AudioFormat(float sampleRate, int sampleSizeInBits, int channels, boolean signed, boolean bigEndian) {
        this(signed ? Encoding.PCM_SIGNED : Encoding.PCM_UNSIGNED, sampleRate, sampleSizeInBits, channels,
                (channels == -1 || sampleSizeInBits == -1) ? -1 : ((sampleSizeInBits + 7) / 8) * channels,
                sampleRate, bigEndian);
    }

    public Encoding getEncoding() { return encoding; }
    public float getSampleRate() { return sampleRate; }
    public int getSampleSizeInBits() { return sampleSizeInBits; }
    public int getChannels() { return channels; }
    public int getFrameSize() { return frameSize; }
    public float getFrameRate() { return frameRate; }
    public boolean isBigEndian() { return bigEndian; }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    public Object getProperty(String key) {
        return properties.get(key);
    }
}
