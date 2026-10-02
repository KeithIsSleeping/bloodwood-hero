# Bloodwood Hero

A rhythm game overlay for chopping bloodwood trees. Falling notes, timing grades, combos, the lot.

![Bloodwood Hero in action](https://raw.githubusercontent.com/KeithIsSleeping/bloodwood-hero/master/preview.gif)

## Why this exists

Bloodwood chopping isn't normal woodcutting. One chop takes two game ticks:

1. **Tick one:** click your own character *twice* to pull the axe back.
2. **Tick two:** click the tree once to chop.

Both pull-back clicks have to land in the same tick. Miss that and the game tells you
"you need to pull your axe back further", and the cycle is wasted.

So it isn't a skill check on your account, it's a skill check on your hands. That's
basically a rhythm game, so here's a rhythm game.

## What you get

**Two target boxes, side by side.** One sits on the axe clickbox on your character, the
other sits on the tree. The tree box is drawn at the same height as the axe box, so your
mouse only ever moves left and right. No dragging it up and down a very tall tree on
every single click.

**Falling notes.** One per click you need to make, dropping into those boxes.

**A grade for every chop.** PERFECT, GREAT or OK, based on how close your click landed to
the centre line. There's a combo counter, a running score, and a burst on the note when
you connect.

**Tree tracking.** Sap left in each tree, colour coded, plus labels for trees waiting to
be collected or ready to chop again. Handy when you're juggling three trees and need to
know which one to walk to.

**A side panel** with your score, current combo, best combo, chops on this tree, and your
timing window next to your ping.

## The timing window

You don't get the whole tick to click in. The click has to reach the server before the
tick ends, so the usable slice is the tick, minus your ping, minus the small delay before
the client actually sends the click, minus the gap the second pull-back click needs.

Rather than making you guess that number or drag a slider until it feels right, the
plugin measures your real ping and sizes the window from it. It uses RuneLite's own world
hopper ping utility, only checks while you're actually at the trees, and does it at most
once every 30 seconds.

## Settings

Recolour the lanes, change how far ahead notes appear, and toggle the grading bands, sap
counters, ground pulse and score panel. Everything is on by default except showing the
track while you're not chopping.

## What it does not do

To stay inside Jagex's third party client rules:

- It does not click for you.
- It does not create, alter or block any mouse or keyboard input.
- It does not add, remove or reorder right click menu options.
- It gives no combat, boss or prayer help.

You still make and time every click yourself. The plugin only draws on screen.

## Requirements

Bloodwood trees need **The Blood Moon Rises**, **77 Woodcutting** and an empty bucket.
They're on Vampyrium.

## Credits

Bloodwood trees and Vampyrium are Jagex's. Ping measurement uses RuneLite's own world
hopper utility.
