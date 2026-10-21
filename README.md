# Mes parcours

An Android app that records your walks.

You tap "start" before you leave and "stop" when you get home. In between, the app
follows your position and draws your path on a map. That is all it does — but it does
it well, and without sending anything to anyone.

The app follows the language of your phone: English, French, Spanish, Portuguese,
German, Italian, Russian, Chinese, Japanese, Arabic and Hindi.

---

## What it does

**It records your trip.** Walking, cycling, driving, whatever. The screen can turn off,
you can switch to another app: the recording continues. If your phone restarts along
the way, the recording picks up on its own where it stopped.

**It shows you where you are.** During the walk: distance covered, your speed, your
altitude. Afterwards: duration, average and maximum speeds, and how much you climbed
and descended.

**It displays your tracks on a map.** Classic map or satellite view. The map can stay
oriented north, or rotate in the direction you are heading. You can show several tracks
at once to compare them, and give each one the color you like.

**It opens and saves GPX and KML files.** These are the formats everybody uses — Google
Earth, sports watches, hiking sites. You can import a track someone sent you, or export
your own to share them. Nothing is simplified along the way: every point is kept, even
in a huge file.

**It can merge, split and trim.** Combine several tracks into one, put back in
chronological order — handy when a long outing ended up cut into pieces. Or the
opposite: split a track into several, at each segment or at each long pause. That is
what a Google Earth file gathering dozens of trips in one block needs. Or trim the first
and last minutes of a recording you forgot to stop.

**It backs up your outings by itself.** At the end of each recording, a file is dropped
in your `Downloads` folder, ready to be picked up or sent wherever you want.

**It goes dark when it is dark.** Either by following your phone's setting, or by
computing the real sunrise and sunset times where you are. It can even recognize a
tunnel and darken for as long as you are in it.

**It corrects altitude.** A phone's GPS is off by about fifty meters, systematically.
The app corrects it, to show the altitude you would read on a map or a sign.

---

## Your data stays on your phone

No account to create. No password. No server.

Your tracks are stored in your phone and **never leave it**, unless you decide to export
a file yourself. There are no usage statistics, no ads, no tracking of any kind. The app
does not know who you are and does not try to.

It needs the internet for two things only: displaying the map background, and checking
whether a new version exists.

> **One exception to know about.** If you turn on satellite view, the images come from
> Google's servers — so the areas you look at in satellite view go through them. The
> classic map comes from OpenStreetMap. If that bothers you, stay on the classic map.

---

## Installing the app

It is not on the Play Store. It takes three steps:

1. Go to the [releases page](https://github.com/ToftMalone/Mes-parcours/releases) and
   download the `.apk` file of the latest version.
2. Open it. Android will ask your permission to install an app that does not come from
   the Play Store — that is normal, accept.
3. It is installed.

After that, the app tells you by itself when a new version comes out, and offers to
install it. Your tracks are kept from one version to the next.

---

## Permissions requested

| What it asks for | Why |
| --- | --- |
| Your location | This is the very point of the app |
| Background location | Keep recording while the screen is off |
| Notifications | Show that the recording is running, and warn you if the GPS is lost |
| Internet | Display the map, and look for updates |
| Phone startup | Resume a recording interrupted by a restart |
| Install apps | Install the updates the app offers you |

---

## For those interested in the code

It is an Android app written in Kotlin, with Jetpack Compose for the interface and
OpenStreetMap for the maps. It builds with Android Studio, or on the command line:

```bash
./gradlew assembleDebug        # build the app
./gradlew testDebugUnitTest    # run the tests
```

You need an Android SDK with API 36, and a `local.properties` file at the root saying
where it is:

```properties
sdk.dir=/path/to/Android/Sdk
```

[CLAUDE.md](CLAUDE.md) documents the internals in detail: how the code is organized,
the technical choices, and the traps not to reintroduce.

The code, its comments and the documentation are in English; the app itself is
translated (see "Languages" in CLAUDE.md to add one).

---

## License

[GPL-3.0](LICENSE) — free to use, modify and redistribute, provided modified versions
stay free too.

Developed by ToftMalone, with Claude. Dedicated to my father, Thierry.
