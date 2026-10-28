# fpv-drone

This is the repo for Minecraft FPV mod ported to MC version 26.3. It is still a work in progress.

### Progress

- [x] Camera rotation with camera tilt
- [x] Basic drone physics
- [x] Drone collision and dimensions on client and server
- [x] Controller support
- [x] Display controller inputs on HUD
- [x] Advanced drone physics
- [x] FPV settings
- [x] Drone Mode is floating head
- [ ] Drone Mode is drone model
- [x] Race gates
- [x] Hide vanilla HUD elements in drone mode.
- [x] Bounce on collision.
- [x] `/race tp` command to teleport to a track.
- [x] `/race delete` command to delete a track.

### Running

If you would like to run this project, clone the repo and open it in IntelliJ. 

Then in a console, run `./gradlew runClient`.

Note that in `build.gradle`, there is a line:

```
jvmArgs '-XstartOnFirstThread'
```

Remove this if you are not on mac. It is only needed on mac.

### Building

To produce a .jar, run `./gradlew build`.

The output will be in:

```
build/libs/
```