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
- [ ] Drone Model
- [ ] Race gates

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