# Bloodwood Hero

A falling-note rhythm track for chopping bloodwood trees.

![Bloodwood Hero in action](https://raw.githubusercontent.com/KeithIsSleeping/bloodwood-hero/master/preview.gif)

Chopping a bloodwood is not ordinary woodcutting. Each chop is a two-tick cycle: on one
tick you click your own character **twice** to pull the axe back, and on the next you
click the tree once to chop. Both pull-back clicks have to land inside the same game
tick, and if they do not, the game tells you to pull your axe back further and the cycle
is lost.

That is a rhythm. This plugin draws it as one.

## What it shows

**Two targets, side by side.** One sits on the axe clickbox, the other on the tree, and
the second is placed level with the first rather than where the tree's own clickbox
centre would put it. A bloodwood is tall, so following the cycle would otherwise mean
dragging the mouse up and down between every click. Level targets make the whole cycle a
left-right movement with nothing to aim at vertically.

**Notes that fall into them,** one per click, spaced as the clicks themselves need to be
spaced. A note inside the box can be clicked, and whatever has to follow that click will
still fit — that is the one promise the box makes, and the window is sized so it holds.

**A grade for every chop.** Perfect, great or ok, from how close the click landed to the
centre line, with a combo, a running score and a burst on the note as it is struck. Hit
enough in a row and it will say so.

**Sap counters on every tree.** How much is left, coloured so a tree that only needs one
more tap reads differently from one that needs several, and labelled when a tree is
waiting to be collected or is ready to chop again. Knowing which of the three to walk to
is most of the work of running them all at once.

## The timing window

The usable part of a game tick is not the whole of it.

A tick begins on the server, and your client sees it one trip later. A click you make
does not go straight onto the wire either — it waits for the client to notice the button
and for the next outbound packet to carry it. Put together, a click is handled in the
tick you think you are clicking in only while it is made earlier than the round trip plus
that delay before the tick ends.

The pull-back needs two clicks rather than one, so the room the second needs comes off as
well. What is left is the window, and that is what the box is drawn at.

The plugin measures your ping and sizes the window from it rather than asking you to
guess, and shows both figures in its panel so you can check one against the other.

## Settings

Lane colours, note lookahead, the grading bands, the sap counters, the ground pulse and
the score panel can all be turned off or recoloured. Everything is on by default except
showing the track while you are not chopping.

## What it does not do

This is a passive visual overlay. It does not click for you, does not generate or consume
input, does not add, remove or reorder menu entries, and gives no combat, boss or prayer
assistance. Every click is still yours to make and still yours to time — it only draws
the rhythm that was always there.

## Requirements

Bloodwood trees need **The Blood Moon Rises**, 77 Woodcutting and an empty bucket. They
are found on Vampyrium.

## Credits

Bloodwood trees and Vampyrium are the work of Jagex. Ping measurement uses RuneLite's own
`worldhopper` utility.
