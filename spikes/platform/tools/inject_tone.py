#!/usr/bin/env python3
"""Feed a known tone into the emulator microphone through the emulator gRPC API (injectAudio).

Needs: an emulator started WITHOUT -no-audio and WITH `-grpc <port>` (tools/boot_audio.sh does that),
and Python stubs generated from $ANDROID_HOME/emulator/lib/emulator_controller.proto:
  python -m venv V && V/bin/pip install grpcio grpcio-tools
  V/bin/python -m grpc_tools.protoc -I<lib> --python_out=S --grpc_python_out=S emulator_controller.proto
Usage: inject_tone.py <stub-dir> <grpc-port> <seconds> [freq_hz=440] [amplitude=8000]
The tone is 16 kHz mono S16; the recorder in the spike reports peak/nonZero, so a peak near
<amplitude> proves real samples reach AudioRecord (and are not silenced to zero).
"""
import math, struct, sys, time

stub_dir, port, secs = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
freq = float(sys.argv[4]) if len(sys.argv) > 4 else 440.0
amp = int(sys.argv[5]) if len(sys.argv) > 5 else 8000
sys.path.insert(0, stub_dir)
import grpc  # noqa: E402
import emulator_controller_pb2 as pb  # noqa: E402
import emulator_controller_pb2_grpc as pbg  # noqa: E402

RATE, CHUNK_MS = 16000, 20
fmt = pb.AudioFormat(samplingRate=RATE, channels=pb.AudioFormat.Mono, format=pb.AudioFormat.AUD_FMT_S16,
                     mode=pb.AudioFormat.MODE_UNSPECIFIED)  # REAL_TIME is marked experimental


def packets():
    n = int(RATE * CHUNK_MS / 1000)
    total = int(secs * 1000 / CHUNK_MS)
    t0 = time.time()
    for k in range(total):
        samples = [int(amp * math.sin(2 * math.pi * freq * (k * n + i) / RATE)) for i in range(n)]
        yield pb.AudioPacket(format=fmt, timestamp=int(time.time() * 1e6),
                             audio=struct.pack("<%dh" % n, *samples))
        # real-time pacing: the emulator buffer holds ~300 ms
        lag = t0 + (k + 1) * CHUNK_MS / 1000 - time.time()
        if lag > 0:
            time.sleep(lag)


# boot_audio.sh starts gRPC with -grpc-use-token. The bearer token is grpc.token in the emulator's
# discovery file ($XDG_RUNTIME_DIR/avd/running/pid_<pid>.ini) whose grpc.port matches.
import glob, os  # noqa: E402
token = None
for f in glob.glob(os.path.join(os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}"), "avd/running/pid_*.ini")):
    kv = dict(l.strip().split("=", 1) for l in open(f) if "=" in l)
    if kv.get("grpc.port") == str(port):
        token = kv.get("grpc.token")
if not token:
    sys.exit(f"no discovery file with grpc.port={port}")
ch = grpc.insecure_channel(f"localhost:{port}")
stub = pbg.EmulatorControllerStub(ch)
md = [("authorization", f"Bearer {token}")]
# Without this the emulator zeroes microphone input (same switch as -allow-host-audio).
stub.setMicrophoneState(pb.MicrophoneState(realAudioEnabled=True), metadata=md)
t = time.time()
stub.injectAudio(packets(), metadata=[("authorization", f"Bearer {token}")])
print(f"injected {secs:.1f}s tone {freq:.0f} Hz amp {amp} in {time.time() - t:.1f}s")
