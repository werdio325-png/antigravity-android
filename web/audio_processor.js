/* global sampleRate, registerProcessor, AudioWorkletProcessor */
const TARGET_SAMPLE_RATE = 16000;

/** @suppress {missingProperties} */
class AudioStreamingProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buffer = [];
    this.sampleCount = 0;
    this.stopped = false;
    this.downsampleRatio = globalThis.sampleRate / TARGET_SAMPLE_RATE;
    this.chunkSizeInSamples = Math.round(globalThis.sampleRate * 0.04);

    this.port.onmessage = (event) => {
      if (event.data === 'stop') {
        this.stopped = true;
        if (this.buffer.length > 0) {
          const pcm = this.processBuffer();
          if (pcm.length > 0) {
            this.port.postMessage(pcm.buffer, [pcm.buffer]);
          }
        }
        this.port.postMessage('done');
      }
    };
  }

  process(inputs, outputs, parameters) {
    if (this.stopped) return false;

    const input = inputs[0]?.[0];
    if (!input || input.length === 0) return true;

    this.buffer.push(new Float32Array(input));
    this.sampleCount += input.length;

    if (this.sampleCount >= this.chunkSizeInSamples) {
      const pcm = this.processBuffer();
      if (pcm.length > 0) {
        this.port.postMessage(pcm.buffer, [pcm.buffer]);
      }
      this.buffer = [];
      this.sampleCount = 0;
    }

    return true;
  }

  processBuffer() {
    const totalSamples = this.buffer.reduce((sum, b) => sum + b.length, 0);
    const flat = new Float32Array(totalSamples);
    let offset = 0;
    for (const buf of this.buffer) {
      flat.set(buf, offset);
      offset += buf.length;
    }

    const downsampledLength = Math.floor(flat.length / this.downsampleRatio);
    const result = new Int16Array(downsampledLength);

    for (let i = 0; i < downsampledLength; i++) {
      const srcIndex = i * this.downsampleRatio;
      const srcFloor = Math.floor(srcIndex);
      const srcCeil = Math.min(srcFloor + 1, flat.length - 1);
      const fraction = srcIndex - srcFloor;
      const interpolated = flat[srcFloor] * (1 - fraction) + flat[srcCeil] * fraction;
      const clamped = Math.max(-1, Math.min(1, interpolated));
      result[i] = clamped < 0 ? clamped * 0x8000 : clamped * 0x7FFF;
    }

    return result;
  }
}

globalThis['registerProcessor']('audio-streaming-processor', AudioStreamingProcessor);
