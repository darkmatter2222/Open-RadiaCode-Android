import serial, time, sys
s = serial.Serial('COM3', 115200, timeout=0.5)
# ESP32 hard-reset via DTR/RTS pulse (esptool's classic reset).
# DTR=EN(reset), RTS=IO0(boot). Drive EN low, leave IO0 high (run mode).
s.setDTR(False); s.setRTS(False); time.sleep(0.1)
s.setDTR(True);  s.setRTS(False); time.sleep(0.1)   # EN low -> reset
s.setDTR(False); s.setRTS(False); time.sleep(0.05)  # release reset, run mode
end = time.time() + 12
out = b''
while time.time() < end:
    d = s.read(4096)
    if d:
        out += d
sys.stdout.buffer.write(out)
s.close()
