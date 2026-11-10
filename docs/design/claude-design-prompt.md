# valpix — design brief

Design the mobile app for **valpix**, an open payment system born in Valparaíso, Chile. Anyone can hold an account, pay by scanning a QR and get paid by showing one. Every account receives 1000 pesos a day, credited continuously, so the balance is always rising. I want to compare proposals: give me **three distinct visual directions** for the same set of screens.

## Who uses it and where

Neighbours paying each other, stalls at the street market (feria), small shops and cafés. It is used standing up, one-handed, outdoors in bright sun, in a hurry, often on a modest Android phone. A person should be able to pay within seconds of opening the app, without reading anything.

## Fixed principles (all three directions must respect these)

- **Pitch black.** True #000 background, made for OLED. White is the only other colour. No accent colour, no brand gradient.
- **Glass.** Controls are translucent glass floating on the black: refraction, soft highlights, depth. Show how glass stays alive on a pure black background, where there is nothing behind it to blur.
- **Hyper-minimal.** No tab bar, no menu, no cards, no charts, no onboarding carousel.
- **No words.** Icons carry no labels. There are no hints, tooltips or explanations; people learn by touching. The only text ever on screen is numbers, people's or shops' names, and times.
- **No crypto vocabulary or imagery.** Nothing about chains, tokens, gas or addresses. It should feel like money, not like a crypto wallet.
- **Native.** It will be built twice, in SwiftUI and in Jetpack Compose. Respect each platform's safe areas and system gestures, and give a simpler fallback for any glass effect that a low-end Android phone could not render.

## The number (the heart of the app)

- The balance sits alone in the centre of the home screen, in a thin, elegant face with tabular figures.
- **Whole pesos are white**: that is real, spendable money.
- **Decimals are gray** and tick upward in real time, four places, fast enough that the last digits blur. The rate is 1000 a day, about 0.0116 per second, so a new white peso completes roughly every 86 seconds.
- Gray always means "not yet real", white means "real". The same language applies to a payment in transit: gray while it travels, white once confirmed.
- Chilean format: dot for thousands, comma for decimals, for example `12.345,2871`. No currency symbol or label.
- Design the two key moments: a gray decimal rolling over into a new white peso, and a payment turning from gray to white.

## Screens to design

1. **Home.** The person's own name at the top centre with a small signal dot beside it, the number in the centre, two round glass buttons at the bottom: scan (to pay) and QR (to get paid). Nothing else. Tapping the name edits it in place; it is what other people see when they pay you or you pay them.
2. **Home, no route.** Paying needs a path to the internet: the phone's own, or a nearby phone's over Bluetooth. When there is none, the dot goes hollow and the buttons dim; the number keeps ticking. Find a wordless way to tell three states apart: own signal, borrowing a neighbour's, none.
3. **History.** Tapping the number slides it up and reveals past payments: name, time, signed amount.
4. **Get paid, amount.** A glass number pad and the amount being typed. The amount can be skipped to show an open QR that a stall could print.
5. **Get paid, QR.** The QR and the amount, nothing else. The code must stay reliably scannable: dark modules on a white field with a quiet margin.
6. **Get paid, received.** The confirmed amount and the payer's name; it should feel good.
7. **Pay, scan.** The camera fills the screen.
8. **Pay, confirm.** The amount, the recipient's name and one glass button approved by face or fingerprint. A very small fee (about 1 peso) may be shown quietly here.
9. **Pay, sending.** The amount in gray while it travels.
10. **Pay, done.** The amount turns white.
11. **Pay, failed.** It did not get through within a minute and cancelled itself. Say this without a wall of text.
12. **Joining.** A newcomer has no account until an existing member brings them in and covers the small cost of creating it. The newcomer's phone shows a QR; the member scans it with the same scan button they use to pay. Design both sides, and the first moment the newcomer sees their own number at zero begin to tick.
13. **Nearby.** As an alternative to QR for paying and for inviting, explore finding the other person over Bluetooth when the two phones are close.

## Sample content

Balance `12.345,2871`. Names: Feria Av. Argentina, Café del cerro, Almacén Yoli, Camila, Tomás. Amounts: +3.500, −1.200, −4.000, −2.350, +800. Any unavoidable word is in Chilean Spanish.

## What I want back

- Three directions, each showing screens 1 to 13 in a phone frame, with one line on what makes that direction different.
- A working prototype of the home screen with the number ticking live at the real rate.
- A short motion note for the number, for gray turning white, and for the glass buttons under a finger.
- A small component sheet: glass button, number-pad key, history row, the signal dot in its three states, the two icons.
- An app icon and a `valpix` wordmark in the same black, white and glass language.

## Care about

- Sunlight legibility. The gray decimals are atmosphere; everything a person needs is in white.
- Reach: both buttons within the thumb's arc on a large phone.
- Accessibility without visible labels: every control still needs a spoken name for screen readers, and the layout must hold with larger text sizes.
