# Troubleshooting

## "No console found" (empty console list)

Sign-in worked, but Microsoft returns zero consoles for the account. This is never a problem of the app or the stick. Check on the console:

1. **Settings > Devices & connections > Remote features > Enable remote features** is on.
2. The console is signed in with the **same Microsoft account** you used at microsoft.com/link. If you have several accounts, check which one the TV screen is signed into.
3. **Settings > General > Personalization > My home Xbox** (make this my home Xbox) is set.
4. The console is on or in **Sleep (Instant-on)** mode, and is connected to the internet. A console shut down with *Energy saving* power mode does not answer.
5. Wait a minute, then go back in the BedPlay menu so it searches again.

## The console shows as "ConnectedStandby"

That is a console in standby that can be woken. Select it and BedPlay starts the session; the wake-up path has not been tested much, so tell us how it goes. If it never wakes, the power mode is probably *Energy saving*.

## Stutter or blocky video

- Use the **5 GHz** Wi-Fi band for the stick and, if you can, a wired connection for the console.
- Open the stats overlay (LB + RB + Y). Packet loss above 0 means the Wi-Fi is the problem; high decode time means the stick is the problem.
- Try the *540p (light)* preset to cap the bitrate lower.

## "Local port 18080 is in use"

Another copy of BedPlay (or an old build) is still running. Force-stop it in *Settings > Apps*, then open BedPlay again.

## The gamepad does not respond

- Pair it in the stick's Bluetooth settings first; it must be recognised as a gamepad by Android.
- Press any button once the stream has started.
- The Back button on the *remote* quits the stream (press twice); the B button on the *gamepad* does not.

## NAT type

Remote Play on the same network worked in our tests with an open NAT. If your console shows *Moderate* or *Strict*, make sure UPnP is enabled on your router, or forward the standard Xbox ports (TCP 53, 80, 3074; UDP 53, 88, 500, 3074, 3544, 4500) to the console's IP and give the console a fixed IP (DHCP reservation). We did not test closed NAT types.

## Fire TV: apps from unknown sources

*Settings > My Fire TV > Developer options*. If the option is missing, go to *About* and click the device name seven times.

## Sign-in code expired

The code lasts about 15 minutes. Restart the app to get a new one.
