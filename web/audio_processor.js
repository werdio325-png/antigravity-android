/* global sampleRate, registerProcessor, AudioWorkletProcessor */
// Keep resampler logic in sync with createMicWorkletKernel in
// src/hooks/voiceAudio.ts.
const TARGET_SAMPLE_RATE = 16000;
const CHUNK_SAMPLES = 640;

/** @suppress {missingProperties} */
class AudioStreamingProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.out = new Int16Array(CHUNK_SAMPLES);
    this.outLen = 0;
    this.acc = 0;
    this.n = 0;
    this.phase = 0;
    this.prev = 0;
    this.hasPrev = false;
    this.stopped = false;

    this.port.onmessage = (event) => {
      if (event.data === 'stop') {
        this.stopped = true;
        if (this.outLen > 0) {
          const rem = this.out.slice(0, this.outLen);
          this.outLen = 0;
          this.port.postMessage(rem.buffer, [rem.buffer]);
        }
        this.port.postMessage('done');
      }
    };
  }

  /** @param {number} s */
  emit(s) {
    const c = Math.max(-1, Math.min(1, s));
    this.out[this.outLen++] = Math.round(c < 0 ? c * 0x8000 : c * 0x7fff);
    if (this.outLen >= CHUNK_SAMPLES) {
      const buf = this.out.buffer;
      this.out = new Int16Array(CHUNK_SAMPLES);
      this.outLen = 0;
      this.port.postMessage(buf, [buf]);
    }
  }

  /**
   * @param {!Array<!Array<!Float32Array>>} inputs
   * @return {boolean}
   */
  process(inputs) {
    if (this.stopped) return false;
    const ch = inputs && inputs[0] && inputs[0][0];
    if (!ch || ch.length === 0) return true;

    const sr =
        globalThis.sampleRate > 0 ? globalThis.sampleRate : TARGET_SAMPLE_RATE;
    if (sr === TARGET_SAMPLE_RATE) {
      for (let i = 0; i < ch.length; i++) this.emit(ch[i]);
    } else if (sr > TARGET_SAMPLE_RATE) {
      for (let i = 0; i < ch.length; i++) {
        this.acc += ch[i];
        this.n++;
        this.phase += TARGET_SAMPLE_RATE;
        if (this.phase >= sr) {
          this.phase -= sr;
          this.emit(this.acc / this.n);
          this.acc = 0;
          this.n = 0;
        }
      }
    } else {
      for (let i = 0; i < ch.length; i++) {
        const s = ch[i];
        if (!this.hasPrev) {
          this.prev = s;
          this.hasPrev = true;
        }
        while (this.phase < TARGET_SAMPLE_RATE) {
          this.emit(
              this.prev + (s - this.prev) * (this.phase / TARGET_SAMPLE_RATE),
          );
          this.phase += sr;
        }
        this.phase -= TARGET_SAMPLE_RATE;
        this.prev = s;
      }
    }
    return true;
  }
}

globalThis['registerProcessor'](
    'audio-streaming-processor',
    AudioStreamingProcessor,
);
