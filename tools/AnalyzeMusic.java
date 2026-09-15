import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Замер треков из assets/music: громкость, характер и поправка для каталога.
 *
 * <p>Запуск из корня: {@code java -cp "libs/*" tools\AnalyzeMusic.java}.
 *
 * <p>Громкость — интегральная по ITU-R BS.1770 (K-взвешивание, блоки 400 мс,
 * абсолютный порог −70 LUFS и относительный −10 LU). Простой RMS здесь врёт:
 * он переоценивает басовые треки, а ухо на низах глуше. Поправка — сколько
 * убрать, чтобы трек сравнялся с самым тихим: музыку только приглушаем, пики
 * у части треков и так упираются в ноль.
 *
 * <p>Характер нужен, чтобы расставить настроения новому треку не на слух
 * вслепую: центроид спектра — насколько трек светлый, доля энергии ниже
 * 150 Гц — насколько он «тёмный», темп — ровный пульс или гул.
 */
public class AnalyzeMusic {

    record Result(String name, double seconds, double lufs, double peakDb,
                  double centroid, double bass, double bpm) {}

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        File dir = new File(args.length > 0 ? args[0] : "assets/music");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".mp3"));
        if (files == null || files.length == 0) {
            System.err.println("no mp3 in " + dir.getAbsolutePath());
            System.exit(1);
        }
        Arrays.sort(files);
        List<Result> results = new ArrayList<>();
        for (File f : files)
            results.add(analyse(f));
        double quietest = results.stream().mapToDouble(Result::lufs).min().orElse(0);
        System.out.printf("%-22s %6s %7s %7s %7s %6s %5s %8s%n",
                "track", "length", "LUFS", "peak", "centr", "bass", "bpm", "gain dB");
        for (Result r : results)
            System.out.printf("%-22s %3d:%02d %7.1f %7.1f %5.0fHz %5.0f%% %5.0f %8.1f%n",
                    r.name, (int) r.seconds / 60, (int) r.seconds % 60, r.lufs, r.peakDb,
                    r.centroid, r.bass * 100, r.bpm, quietest - r.lufs);
    }

    static Result analyse(File f) throws Exception {
        int rate = 0, channels = 0;
        FloatArray left = new FloatArray(), right = new FloatArray();
        try (var in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            Bitstream bs = new Bitstream(in);
            Decoder dec = new Decoder();
            Header h;
            while ((h = bs.readFrame()) != null) {
                SampleBuffer out = (SampleBuffer) dec.decodeFrame(h, bs);
                rate = out.getSampleFrequency();
                channels = out.getChannelCount();
                short[] buf = out.getBuffer();
                int len = out.getBufferLength();
                for (int i = 0; i + channels - 1 < len; i += channels) {
                    left.add(buf[i] / 32768f);
                    right.add(channels > 1 ? buf[i + 1] / 32768f : buf[i] / 32768f);
                }
                bs.closeFrame();
            }
            bs.close();
        }
        float[] l = left.toArray(), r = right.toArray();
        String name = f.getName().substring(0, f.getName().length() - 4);
        double seconds = l.length / (double) rate;
        double peak = 0;
        for (int i = 0; i < l.length; i++)
            peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        double lufs = integratedLoudness(new float[][] { l, channels > 1 ? r : l }, channels > 1 ? 2 : 1, rate);
        float[] mono = new float[l.length];
        for (int i = 0; i < l.length; i++)
            mono[i] = (l[i] + r[i]) * 0.5f;
        double[] spectrum = spectrum(mono, rate);
        return new Result(name, seconds, lufs, 20 * Math.log10(Math.max(1e-9, peak)),
                spectrum[0], spectrum[1], tempo(mono, rate));
    }

    // ---- громкость по ITU-R BS.1770 ----------------------------------------------

    static double integratedLoudness(float[][] ch, int count, int rate) {
        int n = ch[0].length;
        double[][] weighted = new double[count][];
        for (int c = 0; c < count; c++)
            weighted[c] = kWeight(ch[c], rate);
        int block = (int) (0.4 * rate), step = (int) (0.1 * rate);
        List<Double> powers = new ArrayList<>();
        for (int start = 0; start + block <= n; start += step) {
            double sum = 0;
            for (int c = 0; c < count; c++) {
                double z = 0;
                for (int i = start; i < start + block; i++)
                    z += weighted[c][i] * weighted[c][i];
                sum += z / block;
            }
            powers.add(sum);
        }
        double absGate = Math.pow(10, (-70 + 0.691) / 10);
        double mean = 0;
        int kept = 0;
        for (double p : powers)
            if (p > absGate) { mean += p; kept++; }
        if (kept == 0)
            return -70;
        double relGate = mean / kept * Math.pow(10, -10 / 10.0);
        double gated = 0;
        int count2 = 0;
        for (double p : powers)
            if (p > absGate && p > relGate) { gated += p; count2++; }
        return -0.691 + 10 * Math.log10(gated / Math.max(1, count2));
    }

    /** Две биквадратные ступени K-фильтра: полка на верхах и срез низов. */
    static double[] kWeight(float[] x, int rate) {
        double[] shelf = biquadShelf(rate), pass = biquadHighPass(rate);
        return biquad(biquad(toDouble(x), shelf), pass);
    }

    static double[] biquadShelf(int rate) {
        double gain = 3.999843853973347, q = 0.7071752369554196, fc = 1681.974450955533;
        double a = Math.pow(10, gain / 40), w0 = 2 * Math.PI * fc / rate;
        double alpha = Math.sin(w0) / (2 * q), cos = Math.cos(w0), sq = Math.sqrt(a);
        double b0 = a * ((a + 1) + (a - 1) * cos + 2 * sq * alpha);
        double b1 = -2 * a * ((a - 1) + (a + 1) * cos);
        double b2 = a * ((a + 1) + (a - 1) * cos - 2 * sq * alpha);
        double a0 = (a + 1) - (a - 1) * cos + 2 * sq * alpha;
        double a1 = 2 * ((a - 1) - (a + 1) * cos);
        double a2 = (a + 1) - (a - 1) * cos - 2 * sq * alpha;
        return new double[] { b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0 };
    }

    static double[] biquadHighPass(int rate) {
        double q = 0.5003270373238773, fc = 38.13547087602444;
        double w0 = 2 * Math.PI * fc / rate, alpha = Math.sin(w0) / (2 * q), cos = Math.cos(w0);
        double b0 = (1 + cos) / 2, b1 = -(1 + cos), b2 = (1 + cos) / 2;
        double a0 = 1 + alpha, a1 = -2 * cos, a2 = 1 - alpha;
        return new double[] { b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0 };
    }

    static double[] biquad(double[] x, double[] k) {
        double[] y = new double[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double v = k[0] * x[i] + k[1] * x1 + k[2] * x2 - k[3] * y1 - k[4] * y2;
            x2 = x1; x1 = x[i];
            y2 = y1; y1 = v;
            y[i] = v;
        }
        return y;
    }

    static double[] toDouble(float[] x) {
        double[] d = new double[x.length];
        for (int i = 0; i < x.length; i++)
            d[i] = x[i];
        return d;
    }

    // ---- характер ------------------------------------------------------------------

    static final int N = 4096, HOP = 2048;

    /** {центроид в Гц, доля энергии ниже 150 Гц}, взвешенные громкостью окна. */
    static double[] spectrum(float[] mono, int rate) {
        double[] win = hann();
        double[] re = new double[N], im = new double[N];
        double cent = 0, bass = 0, weight = 0;
        for (int off = 0; off + N <= mono.length; off += HOP) {
            double sq = 0;
            for (int i = 0; i < N; i++) {
                sq += mono[off + i] * mono[off + i];
                re[i] = mono[off + i] * win[i];
                im[i] = 0;
            }
            double rms = Math.sqrt(sq / N);
            if (rms < 0.005)
                continue;
            fft(re, im);
            double total = 0, c = 0, b = 0;
            for (int k = 1; k < N / 2; k++) {
                double p = re[k] * re[k] + im[k] * im[k];
                double hz = k * rate / (double) N;
                total += p;
                c += p * hz;
                if (hz < 150)
                    b += p;
            }
            if (total <= 0)
                continue;
            cent += rms * c / total;
            bass += rms * b / total;
            weight += rms;
        }
        return new double[] { cent / Math.max(1e-9, weight), bass / Math.max(1e-9, weight) };
    }

    /** Темп по автокорреляции огибающей атак, 50–180 BPM. */
    static double tempo(float[] mono, int rate) {
        double[] win = hann();
        double[] re = new double[N], im = new double[N], prev = new double[N / 2];
        int frames = (mono.length - N) / HOP;
        double[] flux = new double[Math.max(0, frames)];
        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < N; i++) {
                re[i] = mono[f * HOP + i] * win[i];
                im[i] = 0;
            }
            fft(re, im);
            double sum = 0;
            for (int k = 1; k < N / 2; k++) {
                double m = Math.log1p(1000 * Math.hypot(re[k], im[k]));
                if (m > prev[k])
                    sum += m - prev[k];
                prev[k] = m;
            }
            flux[f] = sum;
        }
        double mean = Arrays.stream(flux).average().orElse(0);
        double perSecond = rate / (double) HOP, best = 0, bpm = 0;
        for (int lag = (int) (perSecond * 60 / 180); lag <= (int) (perSecond * 60 / 50); lag++) {
            double ac = 0;
            for (int i = 0; i + lag < frames; i++)
                ac += (flux[i] - mean) * (flux[i + lag] - mean);
            if (ac > best) {
                best = ac;
                bpm = 60 * perSecond / lag;
            }
        }
        return bpm;
    }

    static double[] hann() {
        double[] w = new double[N];
        for (int i = 0; i < N; i++)
            w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
        return w;
    }

    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1)
                j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }

    static final class FloatArray {
        float[] a = new float[1 << 20];
        int n;

        void add(float v) {
            if (n == a.length)
                a = Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        float[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }
}
