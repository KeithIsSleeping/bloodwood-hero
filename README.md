# Bloodwood Hero

A falling-note rhythm track for chopping bloodwood trees, in the style of a music game.

Chopping a bloodwood is not ordinary woodcutting. Each chop is a two-tick cycle: on one
tick you click your own character **twice** to pull the axe back, and on the next you
click the tree once to chop. Both pull-back clicks have to land inside the same game
tick, and if they do not the game tells you to pull your axe back further and the cycle
is lost. It is a rhythm, and this plugin draws it as one.

## What it shows

- **Two in-world targets**, one on the axe clickbox and one on the tree, placed level
  with each other so the whole cycle is a left-right mouse movement with nothing to aim
  at vertically.
- **Falling notes** through those targets, one per click, spaced as the clicks need to
  be spaced. A note inside the box can be clicked, and whatever has to follow that click
  will still fit.
- **A grade for every chop** - perfect, great or ok - from how close the click was to
  the centre line, with a combo, a score, and a burst on the note when it is struck.
- **Sap counters** on every bleeding tree, so you can see at a glance which one needs
  tapping, which is holding a full bucket and which is ready to chop again.
- **A ground pulse** at the foot of the tree on each chop.

## The timing window

The usable part of a game tick is the whole of it less the round trip to the server: a
click made less than one round trip before the next tick boundary is handled in that
tick, and one made later is not. The spacing the second pull-back click needs comes off
the end of that. The plugin measures your ping and sizes the window from it rather than
guessing, and shows both figures in its panel so you can check them.

## What it does not do

This plugin is a passive visual overlay. It does not click for you, does not generate
or consume input, does not alter or reorder menu entries, and does not provide any
combat, boss or prayer timing assistance. Every click is still yours to make.

## Credits

Bloodwood trees and Vampyrium are the work of Jagex. The ping measurement uses
RuneLite's own `worldhopper` ping utility.
