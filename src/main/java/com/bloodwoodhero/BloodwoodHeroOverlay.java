/*
 * Copyright (c) 2026, KeithIsSleeping
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.bloodwoodhero;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the chopping cycle as lines falling onto the two things you actually click.
 *
 * <p>The targets are not drawn somewhere convenient - they are the real clickboxes. The
 * pull-back box is the axe clickbox NPC that appears while chopping, and the chop box is
 * over the tree. Reading the beat therefore means looking at the same pixels you are
 * about to click, rather than at a track elsewhere and then back again.</p>
 *
 * <p>A box is the window a click has to land in. A line enters the top of it as the tick
 * begins and leaves the bottom when the usable part of that tick is over, so a line
 * overlapping the box means a click there would register right now. The box is shorter
 * than a tick's travel on purpose: a click made late in a tick is often processed in the
 * next one, which for the pull-back drops the second click out of the tick entirely, so
 * only the first part of each tick is worth drawing as clickable. The tree's own clickbox
 * is several times taller again, so only its centre is taken from the tree and its size
 * comes from the axe box.</p>
 *
 * <p>No state is kept here. Overlays render once per frame rather than once per tick, so
 * anything remembered between calls would advance dozens of times a tick; every position
 * is computed from the game clock, which also makes the fall rate the same on any
 * machine.</p>
 */
class BloodwoodHeroOverlay extends Overlay
{
	/**
	 * The last box measured for each tree, and where that tree was when it was measured.
	 *
	 * <p>The clickbox leaves the scene for a tick as one tree hands over to the next, and
	 * both boxes are measured from it, so without this the whole track drops out mid-step
	 * and comes back - which is the jarring part of switching trees.</p>
	 *
	 * <p>The tree's position is kept alongside the box so that the held box can be moved
	 * with it. A remembered screen rectangle on its own goes stale the instant the camera
	 * or the player moves, which is what made earlier attempts snap; shifted by however
	 * far its tree has travelled on screen since, it stays glued to the tree it belongs
	 * to and moves exactly as the scene does.</p>
	 */
	private final Map<Integer, Rectangle> heldBox = new HashMap<>();
	private final Map<Integer, java.awt.Point> heldTreeAt = new HashMap<>();
	private final Map<Integer, Integer> heldTick = new HashMap<>();

	/**
	 * How long a held box stays usable.
	 *
	 * <p>The gap is a single tick in practice. This is a little more than that so a slow
	 * frame cannot expose it, and short enough that a tree genuinely finished with stops
	 * being drawn almost at once.</p>
	 */
	private static final int HOLD_TICKS = 3;

	/** Ticks the grade call-out and the milestone shout each last. */
	private static final double JUDGEMENT_TICKS = 2.0;
	private static final double CALLOUT_TICKS = 3.0;

	/**
	 * The hit burst: how long it lasts and how far its parts travel.
	 *
	 * <p>Half a tick, because it has to be gone before the next note arrives - anything
	 * longer and it stops reading as a strike and starts reading as part of the track.
	 * </p>
	 */
	private static final double HIT_TICKS = 0.5;
	private static final double HIT_STREAK_REACH = 0.55;

	/** How long the box stays red after being clicked with nothing in it. */
	private static final double MISS_TICKS = 0.6;
	private static final Color MISS = new Color(255, 70, 70);

	/** How long the pulse runs, where its wave starts, and how far it reaches. */
	private static final double PULSE_TICKS = 1.8;
	private static final double PULSE_START_TILES = 0.4;
	private static final double PULSE_REACH_TILES = 2.2;

	/**
	 * The travelling wave: how wide it is, how finely it is cut, and its edge profile.
	 *
	 * <p>The bands exist only to carry a gradient across the width of the wave - more of
	 * them is a smoother blend, and at this count the steps are already invisible. The
	 * falloff shapes the brightness between the two edges: above 1 it pulls the glow in
	 * towards the middle, which keeps the band from reading as a rim.</p>
	 */
	private static final double PULSE_WIDTH_TILES = 1.15;
	private static final int PULSE_BANDS = 14;
	private static final double PULSE_FALLOFF = 1.6;

	/** The flush at the foot of the tree: how long it lasts and how strong it starts. */
	private static final double PULSE_FLASH_LIFE = 0.45;
	private static final double PULSE_FLASH_STRENGTH = 0.55;

	/** Points a ring is drawn from, and how far its radius wavers between them. */
	private static final int PULSE_SEGMENTS = 28;
	private static final double PULSE_WOBBLE = 0.06;

	/**
	 * Spacing between the clicks of a single pull-back, as a fraction of a tick.
	 *
	 * <p>Taken from the beat rather than kept here, because it is not only a drawing
	 * decision: the window has to leave room for the second click, so the same figure
	 * sets both the spacing of these notes and the size of the box they fall through.</p>
	 */
	private static final double CLICK_SPACING = BloodwoodBeat.CLICK_SPACING;

	/** Smallest box worth drawing against; below this the target is too far to read. */
	private static final int MIN_BOX_HEIGHT = 6;

	/**
	 * The gap kept between the two boxes, and the narrowest the chop box may be trimmed
	 * to before it stops being worth drawing.
	 *
	 * <p>Both exist for the same moment: the camera swinging the tree round behind the
	 * player, where the two targets would otherwise be drawn on top of each other.</p>
	 */
	private static final int BOX_GAP = 4;
	private static final int MIN_CHOP_BOX_WIDTH = 12;

	/** How faint the landmark outline on a tree you are not working is. */
	private static final int INACTIVE_OUTLINE_ALPHA = 48;

	/**
	 * The character's outline colour: a deep, saturated blue at full strength.
	 *
	 * <p>A colour of its own rather than the track's shade dimmed. Dimming toward black
	 * was tried and made it harder to see, not easier - the scene is dark, so a darkened
	 * colour loses contrast against it rather than gaining any.</p>
	 */
	private static final Color PLAYER_OUTLINE_COLOR = new Color(40, 90, 255);
	private static final int PLAYER_OUTLINE_ALPHA = 255;

	/** How long the wrong-target line stays up, and how big it is drawn. */
	private static final long WRONG_TARGET_MILLIS = 1200;
	private static final float WRONG_TARGET_FONT_SIZE = 14f;

	/** Corner radius of a window, and the length of the sight marks either side of it. */
	private static final int CORNER = 6;
	private static final int CAP = 5;

	/** Thousands separators, since a score runs to five figures inside a trip. */
	private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance();

	/** Height above the sap bar that its figure sits, clear of the bar itself. */
	private static final int SAP_TEXT_OFFSET = 30;

	private final Client client;
	private final BloodwoodHeroPlugin plugin;
	private final BloodwoodHeroConfig config;

	/**
	 * Reusable drawing state.
	 *
	 * <p>Everything here was being rebuilt for every pass of every note of every frame.
	 * None of it carries meaning between frames, so none of it needs to be new each
	 * time - a stroke of a given width is the same object whenever it is asked for.</p>
	 */
	private static final BasicStroke TARGET_HALO = new BasicStroke(4f);
	private static final BasicStroke TARGET_EDGE = new BasicStroke(1.5f);
	private static final BasicStroke LINE_GLOW = round(5f);
	private static final BasicStroke LINE_CORE = round(1.5f);
	private static final BasicStroke LINE_CAP = round(2.5f);

	private static BasicStroke round(float width)
	{
		return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	}

	private static final float[] NOTE_WIDTHS = {7f, 4f, 2f};
	private static final int[] NOTE_ALPHAS = {45, 120, 255};
	private static final BasicStroke[] NOTE_STROKES = strokes(NOTE_WIDTHS, 1f);
	private static final BasicStroke[] NOTE_STROKES_LIVE = strokes(NOTE_WIDTHS, 1.4f);

	private static final Color SHADOW = new Color(0, 0, 0, 220);

	private Font cachedFont;
	private Font cachedBase;
	private float cachedSize = -1;

	/**
	 * Scratch space for the ground wave, reused every frame.
	 *
	 * <p>The wave is the same shape every time it is drawn, so its buffers are allocated
	 * once. None of this carries meaning between frames - it is filled from scratch each
	 * time - but allocating it per frame made the ripple the plugin's largest single
	 * source of garbage.</p>
	 */
	private final double[] innerX = new double[PULSE_SEGMENTS];
	private final double[] innerY = new double[PULSE_SEGMENTS];
	private final double[] outerX = new double[PULSE_SEGMENTS];
	private final double[] outerY = new double[PULSE_SEGMENTS];
	private final Path2D.Double pulseBand =
		new Path2D.Double(Path2D.WIND_EVEN_ODD, PULSE_SEGMENTS * 2 + 2);
	private final Path2D.Double clipPath = new Path2D.Double(Path2D.WIND_EVEN_ODD);

	/** Per-segment angles, which never change, so neither do their sines and cosines. */
	private static final double[] SEG_COS = new double[PULSE_SEGMENTS];
	private static final double[] SEG_SIN = new double[PULSE_SEGMENTS];
	private static final double[] SEG_COS3 = new double[PULSE_SEGMENTS];
	private static final double[] SEG_SIN3 = new double[PULSE_SEGMENTS];

	static
	{
		for (int i = 0; i < PULSE_SEGMENTS; i++)
		{
			double angle = 2 * Math.PI * i / PULSE_SEGMENTS;
			SEG_COS[i] = Math.cos(angle);
			SEG_SIN[i] = Math.sin(angle);
			SEG_COS3[i] = Math.cos(angle * 3);
			SEG_SIN3[i] = Math.sin(angle * 3);
		}
	}

	private static BasicStroke[] strokes(float[] widths, float scale)
	{
		BasicStroke[] made = new BasicStroke[widths.length];
		for (int i = 0; i < widths.length; i++)
		{
			made[i] = new BasicStroke(widths[i] * scale, BasicStroke.CAP_ROUND,
				BasicStroke.JOIN_ROUND);
		}
		return made;
	}

	@Inject
	private BloodwoodHeroOverlay(Client client, BloodwoodHeroPlugin plugin,
		BloodwoodHeroConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			RenderingHints.VALUE_ANTIALIAS_ON);

		// Each hull is resolved once for the whole frame and handed to whatever needs it.
		// It is not a cheap getter - it projects the model's geometry - and the active
		// tree's was being built two or three times a frame by the boxes, the clip and
		// the labels independently. Not cached beyond the frame: the camera moves and
		// models animate between ticks, so a hull is only good for the frame it was
		// taken in.
		GameObject tree = plugin.getActiveTree();
		Shape treeHull = tree == null ? null : tree.getConvexHull();

		// Drawn for every tree in the scene, and regardless of whether you are chopping:
		// the whole point is seeing at a glance which tree still has sap in it.
		if (config.showSap())
		{
			drawSapCounts(graphics, tree, treeHull);
		}

		// No global bail-out here. Every tree's boxes are drawn independently below, so
		// one tree's clickbox being briefly unreadable must not take the whole interface
		// down with it - which is what a single early return did: a blink in the NPC
		// list blanked everything for a tick and brought it all back, right at the moment
		// of a tree switch when the player is most reliant on it.

		// Not gated on chopping any more. The placeholders exist precisely to be there
		// before you arrive, so dropping them the moment you step away from a tree takes
		// away the thing they were added for: walking between trees is exactly when you
		// want to see where the next pair will be.

		// Two separate questions. The boxes are drawn whenever the track belongs to this
		// tree, including the whole walk over to it, so they travel with the player
		// rather than vanishing and reappearing.
		//
		// The notes need more than that: the player has to be chopping, and to have
		// arrived. Asking only whether the boxes are shown let the idle setting bring the
		// notes with them, so standing at a tree doing nothing ran the primer for ever -
		// and that setting promises the boxes, not a rhythm to follow.
		boolean shown = plugin.isChopping() || config.showWhenIdle();
		boolean live = plugin.isChopping() && plugin.isAtActiveTree();

		// Shown whenever the track is, which means while chopping and while crossing to
		// the next tree - both the moments you are deciding where to go. They go only
		// when the track does, with chopping finished altogether.
		if (config.showClickboxes() && shown)
		{
			for (GameObject other : plugin.getTreesById().values())
			{
				if (other != null && other != tree)
				{
					drawInactiveOutline(graphics, other);
				}
			}
		}

		Rectangle pullBox = drawTrack(graphics, tree, plugin.getAxeClickbox(), shown, live);
		if (pullBox == null)
		{
			return null;
		}

		drawGroundPulse(graphics, tree, treeHull);
		drawJudgement(graphics);

		if (config.showCombo())
		{
			drawCombo(graphics, pullBox);
		}
		return null;
	}

	/**
	 * One tree's pair of boxes, with or without the notes falling onto them.
	 *
	 * @param withNotes whether this is the tree being chopped, and so the one whose
	 *                  timing is known
	 * @return the pull-back box, or null if this tree has nothing drawable
	 */
	private Rectangle drawTrack(Graphics2D graphics, GameObject tree, NPC axe,
		boolean shown, boolean withNotes)
	{
		if (tree == null || !shown)
		{
			return null;
		}

		Shape treeHull = tree.getConvexHull();
		if (treeHull == null)
		{
			return null;
		}

		Shape axeHull = axe == null ? null : axe.getConvexHull();

		// Measured directly whenever there is something to measure, so while chopping the
		// box is the clickbox and moves exactly as the scene does.
		Rectangle clickbox = pullBackBox(axeHull);
		clickbox = holdBox(tree, treeHull, clickbox);

		if (clickbox == null)
		{
			return null;
		}

		// A tick's worth of travel is the whole clickbox, but only the first part of a
		// tick reliably registers, so the window drawn is shorter than the distance a
		// note covers in a tick. A note is therefore inside the window for exactly as
		// long as a click there would work.
		int tickHeight = clickbox.height;
		int windowHeight = Math.max(2,
			(int) Math.round(clickbox.height * plugin.effectiveWindow()));
		Rectangle pullBox = new Rectangle(clickbox.x, clickbox.y, clickbox.width, windowHeight);
		Rectangle chopBox = chopBox(pullBox, treeHull);

		// The real click targets, under the timing boxes that stand in for them. The
		// boxes are tidy rectangles in fixed places, which is what makes them readable as
		// a track; these are the awkward shapes the game actually tests a click against.
		//
		// Before a chop has landed there is no beat, so which tick is a pull-back and
		// which is a chop is unknown. Where the window sits inside a tick is not, and any
		// tick is one the player may start a pull-back in - so the pull-back notes fall
		// on every tick until a chop settles the question, and the chop lane stays empty
		// because its timing genuinely is not known yet.
		boolean noBeat = !plugin.hasBeat();

		if (!withNotes)
		{
			// On the way there: the real boxes, in their real place, with nothing falling
			// in them yet. They are the same boxes that will be clicked on arrival, so
			// they travel with the player and settle rather than appearing from nothing -
			// and holding the notes back until the player has actually arrived is what
			// stops the track trying to animate a rhythm onto a box that is still sliding
			// across the screen.
			drawTarget(graphics, pullBox, config.pullBackColor(), false);
			if (chopBox != null)
			{
				drawTarget(graphics, chopBox, config.chopColor(), false);
			}
			return pullBox;
		}

		drawTarget(graphics, pullBox, config.pullBackColor(), false);

		if (chopBox != null)
		{
			drawTarget(graphics, chopBox, config.chopColor(), true);
		}

		// Where your own character can take a click. Drawn after the boxes so the boxes
		// cannot paint over it, and outline only - a wash here tints whatever it is laid
		// across, which is trunk and ground rather than a target of its own.
		//
		// Only the part outside the pull-back box. Inside it the character IS the target,
		// drawn and labelled as such; what is worth seeing is the reach beyond the box,
		// which is the part that takes clicks meant for the wood.
		if (config.showClickboxes() && withNotes)
		{
			Player local = client.getLocalPlayer();
			drawPlayerOutline(graphics, outside(local == null ? null : local.getConvexHull(),
				pullBox));
		}

		drawLines(graphics, pullBox, tickHeight, BloodwoodBeat.PULL_BACK,
			config.pullBackColor(), noBeat);
		drawHitFlash(graphics, pullBox, config.pullBackColor(), BloodwoodBeat.PULL_BACK,
			tickHeight);
		drawMissFlash(graphics, pullBox, BloodwoodBeat.PULL_BACK);

		if (chopBox != null)
		{
			if (!noBeat)
			{
				drawLines(graphics, chopBox, tickHeight, BloodwoodBeat.CHOP,
					config.chopColor(), false);
			}
			drawHitFlash(graphics, chopBox, config.chopColor(), BloodwoodBeat.CHOP,
				tickHeight);
			drawMissFlash(graphics, chopBox, BloodwoodBeat.CHOP);

			// Said out loud, because it is the one failure the player cannot see the
			// cause of: the click was on target and still did the wrong thing, because
			// the axe was in front of the wood at that moment. Without this it reads as
			// the chop box being broken.
			if (plugin.pullBackHitChopBox(chopBox, WRONG_TARGET_MILLIS))
			{
				drawWrongTarget(graphics, chopBox);
			}
		}

		return pullBox;
	}

	/**
	 * The axe clickbox, which is the pull-back target and the unit everything else uses.
	 *
	 * <p>Taken from the clickbox rather than from the model, because the clickbox is what
	 * a click actually has to land in.</p>
	 */
	/**
	 * Carries a tree's box across the tick its clickbox is missing.
	 *
	 * <p>A measured box is simply returned, and remembered along with where its tree was
	 * at the time. When there is nothing to measure, the remembered box is returned
	 * shifted by however far the tree has moved on screen since - so it travels with the
	 * tree rather than hanging where the camera used to be.</p>
	 */
	private Rectangle holdBox(GameObject tree, Shape treeHull, Rectangle measured)
	{
		int treeId = tree.getId();
		int tick = client.getTickCount();
		java.awt.Point treeAt = treeAnchor(tree);
		if (treeAt == null)
		{
			return measured;
		}

		if (measured != null)
		{
			heldBox.put(treeId, measured);
			heldTreeAt.put(treeId, treeAt);
			heldTick.put(treeId, tick);
			return measured;
		}

		Integer when = heldTick.get(treeId);
		Rectangle held = heldBox.get(treeId);
		java.awt.Point was = heldTreeAt.get(treeId);
		if (when == null || held == null || was == null || tick - when > HOLD_TICKS)
		{
			return null;
		}

		return new Rectangle(held.x + (treeAt.x - was.x), held.y + (treeAt.y - was.y),
			held.width, held.height);
	}

	/**
	 * Where a tree is on screen, cheaply.
	 *
	 * <p>Its canvas point rather than its hull, because this is only ever used to measure
	 * how far the tree has moved, and projecting a whole model to answer that would cost
	 * more than everything it is used for. The hull is reserved for the one tree whose
	 * shape is actually being drawn against.</p>
	 */
	private static java.awt.Point treeAnchor(GameObject tree)
	{
		Point at = tree.getCanvasLocation();
		return at == null ? null : new java.awt.Point(at.getX(), at.getY());
	}

	/**
	 * The faint outline of a tree you are not working, where its boxes would be.
	 *
	 * <p>Taken from that tree's own clickbox, live, every frame. Every tree has one while
	 * you are chopping, so these are real boxes in their real places rather than anything
	 * remembered or inferred - and they hold their last position only for the moment the
	 * clickboxes leave the scene during a switch.</p>
	 *
	 * <p>Outlines only, at a fraction of the live track's weight. It is a landmark saying
	 * "the targets are here" - clicking the tree brings the real pair up in the same
	 * place.</p>
	 */
	private void drawInactiveOutline(Graphics2D graphics, GameObject tree)
	{
		// Measured from this tree's own clickbox, which exists while you are chopping
		// anywhere in the grove - every tree carries one. Reading a remembered position
		// instead was the bug: only the tree being worked ever recorded one, so the
		// others had nothing to show, or showed a reading left over from when they were
		// last the active tree and so drew nowhere near their real boxes.
		NPC axe = plugin.getClickboxByTree().get(tree.getId());
		Rectangle measured = axe == null ? null : pullBackBox(axe.getConvexHull());
		if (measured != null)
		{
			heldBox.put(tree.getId(), measured);
		}

		// Held only across the gap where the clickboxes leave the scene, so the boxes
		// stay put for that moment rather than blinking out, and snap to the new reading
		// when they return.
		Rectangle clickbox = measured != null ? measured : heldBox.get(tree.getId());
		if (clickbox == null)
		{
			return;
		}

		// Both boxes, laid out exactly as the live pair is, so clicking this tree brings
		// the real ones up in the same places rather than somewhere near them.
		int windowHeight = Math.max(2,
			(int) Math.round(clickbox.height * plugin.effectiveWindow()));
		Rectangle pullBox = new Rectangle(clickbox.x, clickbox.y, clickbox.width,
			windowHeight);

		graphics.setStroke(TARGET_EDGE);
		outlineBox(graphics, pullBox, config.pullBackColor());

		Shape treeShape = tree.getConvexHull();
		if (treeShape != null)
		{
			Rectangle chop = chopBox(pullBox, treeShape);
			if (chop != null)
			{
				outlineBox(graphics, chop, config.chopColor());
			}
		}
	}

	private void outlineBox(Graphics2D graphics, Rectangle box, Color colour)
	{
		graphics.setColor(alpha(colour, INACTIVE_OUTLINE_ALPHA));
		graphics.draw(new RoundRectangle2D.Float(box.x, box.y, box.width, box.height,
			CORNER, CORNER));
	}

	/**
	 * The part of a shape lying outside a rectangle, or null if none of it does.
	 *
	 * <p>Used to drop the half of the character already inside the pull-back box, which
	 * is the target itself and says nothing by being outlined twice.</p>
	 */
	private static Shape outside(Shape hull, Rectangle exclude)
	{
		if (hull == null)
		{
			return null;
		}
		if (exclude == null || exclude.isEmpty())
		{
			return hull;
		}

		Area area = new Area(hull);
		area.subtract(new Area(exclude));
		return area.isEmpty() ? null : area;
	}

	/** The character's clickable reach, outlined in its own deep blue. */
	private void drawPlayerOutline(Graphics2D graphics, Shape shape)
	{
		if (shape == null)
		{
			return;
		}

		Stroke oldStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(1f));
		graphics.setColor(alpha(PLAYER_OUTLINE_COLOR, PLAYER_OUTLINE_ALPHA));
		graphics.draw(shape);
		graphics.setStroke(oldStroke);
	}

	/**
	 * Tells the player their click hit the axe rather than the tree.
	 *
	 * <p>Drawn over the chop box in the character's own colour, so the warning and the
	 * thing that caused it are plainly the same thing. It says what to do rather than
	 * what went wrong: mid-rhythm there is no time to read an explanation, and the
	 * instruction is the useful half of it.</p>
	 *
	 * <p>The box is outlined in that colour too for the moment it lasts, which says which
	 * of the two overlapping targets took the click.</p>
	 */
	private void drawWrongTarget(Graphics2D graphics, Rectangle chopBox)
	{
		Stroke oldStroke = graphics.getStroke();
		graphics.setStroke(TARGET_EDGE);
		graphics.setColor(alpha(PLAYER_OUTLINE_COLOR, 220));
		graphics.draw(new RoundRectangle2D.Float(chopBox.x, chopBox.y, chopBox.width,
			chopBox.height, CORNER, CORNER));
		graphics.setStroke(oldStroke);

		graphics.setFont(font(graphics, WRONG_TARGET_FONT_SIZE));
		FontMetrics metrics = graphics.getFontMetrics();
		String text = plugin.getWrongTargetShout();
		int x = chopBox.x + (chopBox.width - metrics.stringWidth(text)) / 2;
		int y = chopBox.y - metrics.getDescent();
		drawLabel(graphics, x, y, text, PLAYER_OUTLINE_COLOR);
	}

	private Rectangle pullBackBox(Shape hull)
	{
		if (hull == null)
		{
			return null;
		}
		Rectangle bounds = hull.getBounds();
		return bounds.height < MIN_BOX_HEIGHT ? null : bounds;
	}

	/**
	 * The chop target: on the tree, but level with the pull-back box.
	 *
	 * <p>A bloodwood is a tall object with a correspondingly tall clickbox, which creates
	 * two problems at once. Its height would be read as the length of the click window,
	 * claiming the chop can be clicked across several ticks; and its centre sits well
	 * above the axe clickbox, so following the cycle would mean dragging the mouse up and
	 * down between every click.</p>
	 *
	 * <p>So the box takes its height and its vertical position from the pull-back box -
	 * one tick tall, and on the same line - while its horizontal position comes from the
	 * tree. The whole cycle is then a left-right movement with nothing to aim at
	 * vertically.</p>
	 */
	private Rectangle chopBox(Rectangle pullBox, Shape hull)
	{
		if (hull == null)
		{
			return null;
		}

		Rectangle bounds = hull.getBounds();

		// Where the tree actually is at that height, taken from the hull rather than from
		// its bounding box: a trunk that leans puts its bounding-box centre off the wood,
		// and a click there would miss.
		//
		// Measured by crossing the hull with a single horizontal line rather than by
		// intersecting two areas. Only one number is wanted out of it, and building a
		// boolean edge graph of two shapes every frame to find a midpoint is a great deal
		// of work for it.
		int centreX = crossingCentre(hull, pullBox.y + pullBox.height / 2,
			bounds.x + bounds.width / 2);

		int left = centreX - pullBox.width / 2;
		int right = left + pullBox.width;

		// Pull the near edge clear of the pull-back box when the camera has swung the
		// tree round behind the player and the two land on the same pixels. Trimmed
		// rather than moved: the box is a click target as well as a timing one, so
		// sliding it off the tree to make room would have it pointing at bare ground.
		// Narrower and still on the wood beats the right width in the wrong place.
		if (right > pullBox.x - BOX_GAP && left < pullBox.x + pullBox.width + BOX_GAP)
		{
			if (centreX >= pullBox.x + pullBox.width / 2)
			{
				left = pullBox.x + pullBox.width + BOX_GAP;
			}
			else
			{
				right = pullBox.x - BOX_GAP;
			}
		}

		// Below a usable width there is no honest target left to draw, and the tree is
		// behind the player anyway. Better to show nothing and let them turn the camera
		// than to draw a sliver that cannot be aimed at.
		int width = right - left;
		if (width < MIN_CHOP_BOX_WIDTH)
		{
			return null;
		}

		return new Rectangle(left, pullBox.y, width, pullBox.height);
	}

	/**
	 * The middle of a shape at one height.
	 *
	 * @param fallback used when the line misses the shape entirely, or when the shape is
	 *                 not one that can be walked
	 */
	private static int crossingCentre(Shape shape, int y, int fallback)
	{
		double[] span = crossingSpan(shape, y);
		return span == null ? fallback : (int) Math.round((span[0] + span[1]) / 2);
	}

	/**
	 * The leftmost and rightmost crossings of a shape at one height, or null for a miss.
	 *
	 * <p>Takes every edge that straddles the line and works out where it crosses. For a
	 * convex hull there are exactly two crossings, so this is the shape's exact extent at
	 * that height - the same answer an area intersection gives, without building one.</p>
	 */
	private static double[] crossingSpan(Shape shape, int y)
	{
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;

		double[] point = new double[6];
		double startX = 0;
		double startY = 0;
		double lastX = 0;
		double lastY = 0;

		for (PathIterator it = shape.getPathIterator(null, 1); !it.isDone(); it.next())
		{
			switch (it.currentSegment(point))
			{
				case PathIterator.SEG_MOVETO:
					startX = point[0];
					startY = point[1];
					lastX = startX;
					lastY = startY;
					break;

				case PathIterator.SEG_LINETO:
					double crossing = crossingAt(lastX, lastY, point[0], point[1], y);
					if (!Double.isNaN(crossing))
					{
						min = Math.min(min, crossing);
						max = Math.max(max, crossing);
					}
					lastX = point[0];
					lastY = point[1];
					break;

				case PathIterator.SEG_CLOSE:
					double closing = crossingAt(lastX, lastY, startX, startY, y);
					if (!Double.isNaN(closing))
					{
						min = Math.min(min, closing);
						max = Math.max(max, closing);
					}
					lastX = startX;
					lastY = startY;
					break;

				default:
					// Flattened to straight lines above, so nothing else can appear.
					break;
			}
		}

		return min <= max ? new double[]{min, max} : null;
	}

	/** Where an edge crosses a horizontal line, or NaN if it does not reach it. */
	private static double crossingAt(double x1, double y1, double x2, double y2, int y)
	{
		if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y))
		{
			return x1 + (y - y1) * (x2 - x1) / (y2 - y1);
		}
		return Double.NaN;
	}

	/**
	 * The window, drawn as a soft target rather than as a box with stripes in it.
	 *
	 * <p>The grading used to be three flat bands laid over each other, which muddied into
	 * a different colour wherever they overlapped the lane's own - green over red reads as
	 * olive, and the result said nothing about either. The bands are now one vertical
	 * gradient in the lane's own hue, bright along the centre line and falling away to
	 * nothing at the edges, so accuracy is carried by brightness rather than by stacking
	 * hues that were never meant to mix. The stops still sit exactly on the grade
	 * boundaries, so what is seen is still what is scored.</p>
	 */
	private void drawTarget(Graphics2D graphics, Rectangle box, Color color, boolean graded)
	{
		Shape rounded = new RoundRectangle2D.Float(box.x, box.y, box.width, box.height,
			CORNER, CORNER);

		if (config.showTimingBands() && graded && box.height >= 4)
		{
			paintGradedFill(graphics, box, color, rounded);
		}
		else
		{
			graphics.setColor(alpha(color, 30));
			graphics.fill(rounded);
		}

		// Two passes: a wide, faint halo that lifts the box off the scenery, and a thin
		// crisp edge on top of it. A single hard rectangle is what made this read as a
		// debug overlay rather than as part of the game.
		graphics.setColor(alpha(color, 45));
		graphics.setStroke(TARGET_HALO);
		graphics.draw(rounded);
		graphics.setColor(alpha(color, 220));
		graphics.setStroke(TARGET_EDGE);
		graphics.draw(rounded);

		if (config.showTimingBands())
		{
			drawCentreLine(graphics, box, color, graded);
		}
	}

	/** A vertical gradient whose stops are the grade boundaries. */
	private void paintGradedFill(Graphics2D graphics, Rectangle box, Color color, Shape clip)
	{
		float perfect = (float) BloodwoodJudgement.PERFECT_WINDOW;
		float great = (float) BloodwoodJudgement.GREAT_WINDOW;

		float[] stops = {
			0f, 0.5f - great, 0.5f - perfect, 0.5f, 0.5f + perfect, 0.5f + great, 1f};
		Color[] colors = {
			alpha(color, 0), alpha(color, 26), alpha(color, 70), alpha(color, 140),
			alpha(color, 70), alpha(color, 26), alpha(color, 0)};

		Paint previous = graphics.getPaint();
		graphics.setPaint(new LinearGradientPaint(
			new Point2D.Float(box.x, box.y),
			new Point2D.Float(box.x, box.y + box.height),
			stops, colors));
		graphics.fill(clip);
		graphics.setPaint(previous);
	}

	/** The line the click is aimed at, glowing rather than drawn as a hard white bar. */
	private void drawCentreLine(Graphics2D graphics, Rectangle box, Color color, boolean graded)
	{
		int y = box.y + box.height / 2;
		int left = box.x - 3;
		int right = box.x + box.width + 3;

		// Widest and faintest first, so the line appears to glow out of the gradient
		// rather than sitting on top of it.
		graphics.setColor(alpha(Color.WHITE, graded ? 40 : 28));
		graphics.setStroke(LINE_GLOW);
		graphics.drawLine(left, y, right, y);
		graphics.setColor(alpha(Color.WHITE, graded ? 230 : 150));
		graphics.setStroke(LINE_CORE);
		graphics.drawLine(left, y, right, y);

		// End caps, so it reads as a sight rather than as a stationary note.
		graphics.setColor(alpha(color, 230));
		graphics.setStroke(LINE_CAP);
		graphics.drawLine(left - CAP, y, left, y);
		graphics.drawLine(right, y, right + CAP, y);
	}

	private static Color alpha(Color color, int value)
	{
		return new Color(color.getRed(), color.getGreen(), color.getBlue(),
			Math.max(0, Math.min(255, value)));
	}

	/**
	 * The falling lines for one beat, one per click that beat asks for.
	 *
	 * <p>A line's position is how long is left until its click is due, measured in box
	 * heights at one box height per game tick. When it is due it sits on the top edge, and
	 * a tick later it has crossed to the bottom edge, so the time it overlaps the box is
	 * exactly the time the click is accepted.</p>
	 */
	/**
	 * The notes falling into one lane.
	 *
	 * @param everyTick draw this lane's notes on every tick rather than only on the ticks
	 *                  the beat assigns to it. Used before a chop has established the
	 *                  beat: which tick is a pull-back and which is a chop is not known
	 *                  then, but where the window sits inside a tick is, and any tick is
	 *                  a tick you may start a pull-back in. The notes are therefore
	 *                  truthful about timing while saying nothing about phase.
	 */
	private void drawLines(Graphics2D graphics, Rectangle box, int tickHeight,
		BloodwoodBeat beat, Color color, boolean everyTick)
	{
		int tick = client.getTickCount();
		double now = tick + plugin.getTickProgress();
		int lookahead = config.lookaheadTicks();

		// Brightening once per lane rather than once per pass of every note: it is the
		// same colour each time and the result does not depend on the note.
		Color bright = brighten(color);

		// Furthest first, so the imminent line is drawn over the ones behind it.
		for (int ahead = lookahead; ahead >= 0; ahead--)
		{
			if (!everyTick && plugin.beatAt(tick + ahead) != beat)
			{
				continue;
			}

			int clicks = beat == BloodwoodBeat.PULL_BACK ? beat.getClicks() : 1;
			for (int click = 0; click < clicks; click++)
			{
				// A note that has been struck is gone. Leaving it to carry on falling
				// past its own burst said the click had not landed, which is the opposite
				// of what had just happened.
				//
				// Counted in hits, not clicks: a click that struck nothing took no note
				// with it, and the note it missed is still falling and still the one the
				// next click is aimed at.
				if (ahead == 0 && click < plugin.getHitsThisTick(beat))
				{
					continue;
				}

				double due = tick + ahead + click * CLICK_SPACING;
				double ticksAway = due - now;
				if (ticksAway > lookahead || ticksAway < -1)
				{
					continue;
				}

				// A tick of travel is a whole clickbox, not a whole window: the window is
				// only the part of the tick that registers, so a shorter one has to mean
				// less time rather than slower notes.
				int y = (int) Math.round(box.y - ticksAway * tickHeight);
				drawLine(graphics, box, y, color, bright, ticksAway);
			}
		}
	}

	private void drawLine(Graphics2D graphics, Rectangle box, int y, Color color,
		Color bright, double ticksAway)
	{
		// Brightest as it reaches the window and faded while still on its way, so the line
		// being asked for now is the one that stands out.
		double nearness = Math.max(0,
			Math.min(1, 1 - ticksAway / (config.lookaheadTicks() + 1.0)));
		boolean live = ticksAway <= 0 && ticksAway > -1;

		int left = box.x - 2;
		int right = box.x + box.width + 2;
		BasicStroke[] passes = live ? NOTE_STROKES_LIVE : NOTE_STROKES;

		// Three passes from wide and faint to narrow and bright. A flat bar of one colour
		// reads as a scratch on the screen; the halo is what makes it a note travelling
		// towards something.
		for (int pass = 0; pass < passes.length; pass++)
		{
			int shade = (int) Math.round(NOTE_ALPHAS[pass] * nearness);
			if (shade <= 0)
			{
				continue;
			}
			graphics.setColor(alpha(pass == 2 ? bright : color, shade));
			graphics.setStroke(passes[pass]);
			graphics.drawLine(left, y, right, y);
		}
	}

	/**
	 * The box flashing red when it is clicked with nothing in it.
	 *
	 * <p>Drawn on the box rather than at the click, which is the whole point. A stray
	 * click used to be given the same burst as a hit, placed at wherever in the tick it
	 * landed, so it appeared as a flash at an arbitrary height with nothing under it -
	 * indistinguishable from the track glitching. A miss is not an event with a position;
	 * it is the lane as a whole saying there was nothing there.</p>
	 */
	private void drawMissFlash(Graphics2D graphics, Rectangle box, BloodwoodBeat beat)
	{
		double age = plugin.missAge(beat);
		if (age < 0 || age > MISS_TICKS)
		{
			return;
		}

		double fade = 1 - age / MISS_TICKS;
		fade *= fade;

		graphics.setColor(alpha(MISS, (int) Math.round(90 * fade)));
		graphics.fill(box);
		graphics.setColor(alpha(MISS, (int) Math.round(255 * fade)));
		graphics.setStroke(TARGET_HALO);
		graphics.draw(box);
	}

	/**
	 * The burst a line gives off when it is struck inside the window.
	 *
	 * <p>A hit used to be shown only by the note getting slightly wider and slightly
	 * brighter for the tick it was live, which is both too small to catch out of the
	 * corner of the eye and indistinguishable from the note simply arriving. There was no
	 * way to tell a click that landed from a click that did nothing.</p>
	 *
	 * <p>So the hit is now its own event rather than a change of state: a white core blown
	 * out on the note itself and streaks thrown out past both ends of the box. The note
	 * it struck is consumed at the same moment, because a note that carries on falling
	 * past its own burst says the click did not land.</p>
	 *
	 * <p>It is drawn where the note has reached by the time the click has been handled
	 * rather than where it was when the button went down. Those are the same instant to
	 * the player but not to the client, and using the press put the burst visibly behind
	 * the note it was supposed to be striking.</p>
	 */
	private void drawHitFlash(Graphics2D graphics, Rectangle box, Color color,
		BloodwoodBeat beat, int tickHeight)
	{
		double age = plugin.hitAge(beat);
		if (age < 0 || age > HIT_TICKS)
		{
			return;
		}

		double life = age / HIT_TICKS;
		// Fast out of the gate and slow to die, so the moment of contact is the loudest
		// part of it rather than the middle of the fade.
		double fade = (1 - life) * (1 - life);

		// On the note that was hit, which means allowing for which note it was. The tick's
		// notes are spaced apart rather than stacked, so the second one is that spacing
		// higher up the box than the first at the same point in the tick - and a burst
		// that ignored this drew every click on the first note's line.
		double at = plugin.hitFraction(beat) - plugin.hitClick(beat) * CLICK_SPACING;
		int y = box.y + (int) Math.round(Math.max(0, Math.min(1, at)) * tickHeight);
		Color hot = brighten(brighten(color));

		// Streaks thrown out past the ends of the box, which is what gives the hit a
		// direction and makes it visible even when the eye is on the tree rather than the
		// axe.
		int spread = (int) Math.round(box.width * HIT_STREAK_REACH * easeOut(life));
		int streakAlpha = (int) Math.round(220 * fade);
		if (streakAlpha > 2 && spread > 0)
		{
			graphics.setColor(alpha(hot, streakAlpha));
			graphics.setStroke(new BasicStroke((float) Math.max(1, 4 * fade),
				BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.drawLine(box.x - 4 - spread, y, box.x - 4, y);
			graphics.drawLine(box.x + box.width + 4, y, box.x + box.width + 4 + spread, y);
		}

		// The note itself, blown out to white at the moment of contact and settling back
		// into its own colour as it goes.
		int left = box.x - 2;
		int right = box.x + box.width + 2;
		float[] widths = {14f, 7f, 3f};
		int[] alphas = {
			(int) Math.round(70 * fade),
			(int) Math.round(150 * fade),
			(int) Math.round(255 * fade)};

		for (int pass = 0; pass < widths.length; pass++)
		{
			if (alphas[pass] <= 2)
			{
				continue;
			}
			graphics.setColor(alpha(pass == 2 ? blend(hot, Color.WHITE, fade) : hot,
				alphas[pass]));
			graphics.setStroke(new BasicStroke((float) (widths[pass] * (0.4 + fade)),
				BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.drawLine(left, y, right, y);
		}
	}

	/** Quick at first and easing off, which is how a struck thing actually moves. */
	private static double easeOut(double t)
	{
		double at = Math.max(0, Math.min(1, t));
		return 1 - (1 - at) * (1 - at);
	}

	/**
	 * A bold font of the given size, reusing the last one when nothing has changed.
	 *
	 * <p>Deriving a font allocates one, and this runs several times a frame. Sizes here
	 * are animated but land on the same handful of values from one frame to the next, so
	 * remembering the last is enough to take almost all of the churn out without having
	 * to cache a table of them.</p>
	 */
	private Font font(Graphics2D graphics, float size)
	{
		Font base = graphics.getFont();
		if (cachedFont == null || cachedBase != base || cachedSize != size)
		{
			cachedBase = base;
			cachedSize = size;
			cachedFont = base.deriveFont(Font.BOLD, size);
		}
		return cachedFont;
	}

	/** A lighter cast of a colour, for the core of a glow. */
	private static Color brighten(Color color)
	{
		return new Color(
			Math.min(255, color.getRed() + 70),
			Math.min(255, color.getGreen() + 70),
			Math.min(255, color.getBlue() + 70));
	}


	/**
	 * A warm pulse spreading across the ground out of the foot of the tree.
	 *
	 * <p>Built as a ring in world coordinates and projected point by point, so it lies on
	 * the ground and leans with the camera the way the ground does. The earlier version
	 * drew the tree's own tile outline, which is the tree's clickbox rather than the
	 * ground around it - a hard quadrilateral that announced itself as an overlay instead
	 * of looking like something the chop had done to the floor.</p>
	 *
	 * <p>The radius carries a small per-segment wobble so the edge shivers rather than
	 * expanding as a clean circle, and the colour is a warm earth tone that sits on the
	 * blood ground rather than fighting it.</p>
	 */
	private void drawGroundPulse(Graphics2D graphics, GameObject tree, Shape treeHull)
	{
		if (plugin.getChopAnimTick() < 0)
		{
			return;
		}

		double age = client.getTickCount() - plugin.getChopAnimTick() + plugin.getTickProgress();
		if (age > PULSE_TICKS || age < 0)
		{
			return;
		}

		LocalPoint base = tree == null ? null : tree.getLocalLocation();
		WorldView wv = client.getTopLevelWorldView();
		if (base == null || wv == null)
		{
			return;
		}

		double life = age / PULSE_TICKS;
		Color warm = config.pulseColor();
		int plane = wv.getPlane();

		Shape restore = graphics.getClip();
		clipBehind(graphics, treeHull);
		try
		{
			drawPulseRings(graphics, wv, base, plane, life, warm, age);
		}
		finally
		{
			graphics.setClip(restore);
		}
	}

	/**
	 * Cut a tree's own silhouette out of what may be painted.
	 *
	 * <p>Overlays are drawn after the scene with nothing to test depth against, so a ring
	 * spreading from the foot of a tree is painted straight over the trunk it should be
	 * passing behind - which is what made the pulse look stuck to the front of the tree
	 * rather than lying on the floor.</p>
	 *
	 * <p>Removing the tree from the clip gives back the one piece of depth that matters:
	 * the far side of the ring vanishes behind the trunk and reappears either side of it.
	 * The hull is convex, so it is a little fuller than the tree really is, but it tapers
	 * to the base - and the base is where the ring passes - so the part that is cut is
	 * close to the trunk itself.</p>
	 *
	 * <p>Done with an even-odd path rather than by subtracting one area from another.
	 * The result is the same and the clip is set once either way, but the area version
	 * builds a full boolean edge graph of two shapes on every frame the pulse is alive -
	 * which, with a pulse lasting most of a chop cycle, is almost every frame.</p>
	 */
	private void clipBehind(Graphics2D graphics, Shape silhouette)
	{
		if (silhouette == null)
		{
			return;
		}

		clipPath.reset();

		Shape current = graphics.getClip();
		if (current != null)
		{
			clipPath.append(current, false);
		}
		else
		{
			clipPath.append(new Rectangle(
				client.getCanvasWidth(), client.getCanvasHeight()), false);
		}

		// Appended as a second subpath under the even-odd rule, which makes the enclosed
		// area a hole rather than a second region to paint.
		clipPath.append(silhouette, false);
		graphics.setClip(clipPath);
	}

	private void drawPulseRings(Graphics2D graphics, WorldView wv, LocalPoint base,
		int plane, double life, Color warm, double age)
	{
		// The wave is a band travelling outwards, not a set of hoops. Its leading edge
		// runs out to the full reach while a trailing edge follows a fixed distance
		// behind, so what expands is a ring of finite width rather than a circle being
		// resized - which is what the three outlined circles always looked like.
		double head = PULSE_START_TILES + life * PULSE_REACH_TILES;
		double tail = Math.max(0, head - PULSE_WIDTH_TILES);

		// Only the two edges of the wave are projected. Projecting every boundary between
		// bands meant fifteen rings of twenty-eight points - over four hundred
		// projections a frame, running almost continuously, since a pulse lasts most of a
		// chop cycle. The bands in between are straight lines between corresponding
		// points on those two edges, so interpolating them on screen costs nothing and
		// cannot be told apart on ground this flat.
		if (!projectRing(wv, base, plane, tail, age, innerX, innerY)
			|| !projectRing(wv, base, plane, head, age, outerX, outerY))
		{
			return;
		}

		// Fading as it goes, so the ripple spends itself rather than stopping.
		double decay = (1 - life) * (1 - life);
		Color crest = brighten(warm);

		for (int i = 0; i < PULSE_BANDS; i++)
		{
			// Where this band sits across the width of the wave, 0 at the trailing edge
			// and 1 at the leading one.
			double from = i / (double) PULSE_BANDS;
			double to = (i + 1) / (double) PULSE_BANDS;
			double u = (from + to) / 2;

			// Soft at both edges and full in the middle, so the band has shoulders
			// instead of a rim. This is what makes it read as a glow rather than a hoop.
			double profile = Math.pow(Math.sin(Math.PI * u), PULSE_FALLOFF);

			int bandAlpha = (int) Math.round(warm.getAlpha() * decay * profile);
			if (bandAlpha <= 2)
			{
				continue;
			}

			// Hotter towards the leading edge, cooling off behind it - the gradient that
			// gives the ripple a direction to travel in.
			graphics.setColor(alpha(blend(warm, crest, u), bandAlpha));
			graphics.fill(band(from, to));
		}

		// A short-lived flush at the foot of the tree, gone well before the ripple is, so
		// the chop reads as having happened at the trunk and travelled outwards.
		double flash = 1 - Math.min(1, life / PULSE_FLASH_LIFE);
		if (flash > 0)
		{
			graphics.setColor(alpha(crest,
				(int) Math.round(warm.getAlpha() * flash * flash * PULSE_FLASH_STRENGTH)));
			graphics.fill(band(-1, 0));
		}
	}

	/**
	 * One band of the wave, as a ring between two interpolated edges.
	 *
	 * <p>Built into a single reused path rather than a fresh shape each time, and wound
	 * so the inner edge cuts a hole rather than being filled over - a band drawn as two
	 * overlapping fills would stack its own translucency.</p>
	 *
	 * <p>A band starting below zero is a disc rather than a ring: it has no hole, which
	 * is what the flush at the foot of the tree wants.</p>
	 */
	private Shape band(double from, double to)
	{
		pulseBand.reset();

		for (int i = 0; i < PULSE_SEGMENTS; i++)
		{
			double x = innerX[i] + (outerX[i] - innerX[i]) * to;
			double y = innerY[i] + (outerY[i] - innerY[i]) * to;
			if (i == 0)
			{
				pulseBand.moveTo(x, y);
			}
			else
			{
				pulseBand.lineTo(x, y);
			}
		}
		pulseBand.closePath();

		if (from >= 0)
		{
			for (int i = PULSE_SEGMENTS - 1; i >= 0; i--)
			{
				double x = innerX[i] + (outerX[i] - innerX[i]) * from;
				double y = innerY[i] + (outerY[i] - innerY[i]) * from;
				if (i == PULSE_SEGMENTS - 1)
				{
					pulseBand.moveTo(x, y);
				}
				else
				{
					pulseBand.lineTo(x, y);
				}
			}
			pulseBand.closePath();
		}

		return pulseBand;
	}

	/** A colour part of the way between two others. */
	private static Color blend(Color from, Color to, double t)
	{
		double at = Math.max(0, Math.min(1, t));
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * at),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * at),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * at));
	}

	/**
	 * Projects one circle on the ground into the given buffers, a point at a time.
	 *
	 * <p>Point by point because a circle on the ground is not a circle on screen - it
	 * leans with the camera, and only projecting each point separately keeps it lying on
	 * the floor. The per-segment angles never change, so their sines and cosines are
	 * worked out once for the class rather than three times per point per frame.</p>
	 *
	 * @return false if any part of the ring is off the scene, where there is no honest
	 *         way to close it
	 */
	private boolean projectRing(WorldView wv, LocalPoint centre, int plane, double tiles,
		double age, double[] xs, double[] ys)
	{
		int radius = (int) Math.round(Perspective.LOCAL_TILE_SIZE * tiles);

		// The wobble is what makes it a shiver rather than a clean expansion. Its phase
		// is the same for every point of a ring, so the two terms that depend on it are
		// worked out once rather than per segment.
		double phaseSin = Math.sin(age * 9);
		double phaseCos = Math.cos(age * 9);

		for (int i = 0; i < PULSE_SEGMENTS; i++)
		{
			double wobble = 1
				+ (SEG_SIN3[i] * phaseCos + SEG_COS3[i] * phaseSin) * PULSE_WOBBLE;
			int reach = (int) Math.round(radius * wobble);

			LocalPoint at = new LocalPoint(
				centre.getX() + (int) Math.round(SEG_COS[i] * reach),
				centre.getY() + (int) Math.round(SEG_SIN[i] * reach), wv);

			Point canvas = Perspective.localToCanvas(client, at, plane);
			if (canvas == null)
			{
				return false;
			}
			xs[i] = canvas.getX();
			ys[i] = canvas.getY();
		}
		return true;
	}

	/**
	 * The grade for the last chop, rising and fading above the player.
	 *
	 * <p>Rises as it fades so it reads as a call-out rather than as a label stuck to the
	 * character, and starts oversized so the moment it appears is the loudest part of it.
	 * </p>
	 */
	private void drawJudgement(Graphics2D graphics)
	{
		BloodwoodJudgement judgement = plugin.getLastJudgement();
		if (judgement == null || plugin.getLastJudgementTick() < 0)
		{
			return;
		}

		double age = client.getTickCount() - plugin.getLastJudgementTick()
			+ plugin.getTickProgress();
		String callout = plugin.getCallout();

		// The grade and the shout are given at the same moment but are not the same
		// length, so the longer of the two decides when there is nothing left to draw.
		// Returning on the grade's own span meant the shout's span could never be
		// reached: it was declared a tick longer and silently cut to the grade's.
		double longest = callout == null ? JUDGEMENT_TICKS
			: Math.max(JUDGEMENT_TICKS, CALLOUT_TICKS);
		if (age > longest)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		Point anchor = local.getCanvasTextLocation(graphics, judgement.getLabel(),
			local.getLogicalHeight() + 40);
		if (anchor == null)
		{
			return;
		}

		double life = 1 - age / JUDGEMENT_TICKS;
		// A short overshoot at the start, settling quickly - the pop that makes it land.
		double pop = age < 0.3 ? 1.5 - age : 1.2;
		float size = (float) (16 * pop);
		int rise = (int) Math.round(26 * (1 - life));

		int y = anchor.getY() - rise;
		int alpha = (int) Math.round(255 * Math.min(1, life * 2));
		FontMetrics metrics;

		if (age <= JUDGEMENT_TICKS)
		{
			graphics.setFont(font(graphics, size));
			metrics = graphics.getFontMetrics();
			int x = anchor.getX() - metrics.stringWidth(judgement.getLabel()) / 2;

			Color color = judgement.getColor();
			graphics.setColor(new Color(0, 0, 0, Math.round(alpha * 0.7f)));
			graphics.drawString(judgement.getLabel(), x + 2, y + 2);
			graphics.setColor(
				new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
			graphics.drawString(judgement.getLabel(), x, y);
		}
		else
		{
			graphics.setFont(font(graphics, size));
			metrics = graphics.getFontMetrics();
		}

		// The milestone shout, on top of the grade, fading on its own clock.
		if (callout != null && age < CALLOUT_TICKS)
		{
			double shoutLife = 1 - age / CALLOUT_TICKS;
			int shoutAlpha = (int) Math.round(255 * Math.min(1, shoutLife * 2));

			graphics.setFont(font(graphics, (float) (20 * pop)));
			FontMetrics big = graphics.getFontMetrics();
			int cx = anchor.getX() - big.stringWidth(callout) / 2;
			int cy = y - metrics.getHeight() - 4;
			graphics.setColor(new Color(0, 0, 0, Math.round(shoutAlpha * 0.7f)));
			graphics.drawString(callout, cx + 2, cy + 2);
			graphics.setColor(new Color(255, 255, 255, shoutAlpha));
			graphics.drawString(callout, cx, cy);
		}
	}

	/**
	 * The sap left in each bleeding tree, above the bar the game draws for it.
	 *
	 * <p>Placed against the bar rather than the tree's clickbox because the bar is what
	 * the player is already watching, and because a bloodwood is tall enough that the
	 * centre of its clickbox is nowhere near it. The bar is an NPC of its own, so it can
	 * be anchored to directly instead of guessed at.</p>
	 *
	 * <p>Coloured by how full that bar is rather than by the sap figure. The two say
	 * different things: the bar runs down and refills repeatedly while the total left to
	 * tap only falls, and it is the bar emptying that says when to act.</p>
	 */
	private void drawSapCounts(Graphics2D graphics, GameObject active, Shape activeHull)
	{
		graphics.setFont(font(graphics, config.sapFontSize()));

		// While it is draining, the figure is anchored to the bar the game draws for it.
		for (NPC headbar : plugin.getHeadbars())
		{
			// One lookup rather than two. Each one scans every tree in the clearing to
			// find the nearest, and asking twice per bar did that six times over for
			// nothing.
			int treeId = plugin.getTreeIdForHeadbar(headbar);
			int sap = plugin.getSap(treeId);
			if (sap <= 0)
			{
				continue;
			}

			// The stage is named beside the figure rather than instead of it, so a tapping
			// tree reads the same way as one waiting to be collected or chopped. Dropped
			// only once a tap already running will finish the tree, because the word is
			// an instruction and there is then no further tap to ask for.
			boolean finalTap = plugin.isFinalTap(treeId);
			String text = finalTap
				? String.valueOf(sap)
				: sap + " " + BloodwoodStage.TAPPING.getLabel();
			Point anchor = headbar.getCanvasTextLocation(graphics, text,
				headbar.getLogicalHeight() + SAP_TEXT_OFFSET);
			if (anchor != null)
			{
				drawLabel(graphics, anchor.getX(), anchor.getY(), text,
					sapColor(finalTap, plugin.getBarFraction(headbar)));
			}
		}

		drawStageLabels(graphics, active, activeHull);
	}

	/**
	 * What the tree is waiting for once there is no sap figure to show.
	 *
	 * <p>Drawn on the tree itself rather than on the bar, because the bar is gone by
	 * then - it belongs to the sap, and the sap is what has run out.</p>
	 *
	 * <p>Collect is shown for any tree holding a full bucket, since knowing which of the
	 * six to walk to is the point. Chop is shown only for the tree being worked: it is
	 * the resting state of every tree nobody is touching, and six of them saying so at
	 * once would be noise rather than information.</p>
	 */
	private void drawStageLabels(Graphics2D graphics, GameObject active, Shape activeHull)
	{
		for (Map.Entry<Integer, GameObject> entry : plugin.getTrees().entrySet())
		{
			BloodwoodStage stage = plugin.getStage(entry.getKey());
			// Tapping names itself beside the sap figure, which is anchored to the bar.
			if (stage == BloodwoodStage.TAPPING)
			{
				continue;
			}
			if (stage == BloodwoodStage.CHOP && entry.getValue() != active)
			{
				continue;
			}

			// The active tree's hull was already taken for this frame, so it is reused
			// rather than rebuilt.
			Shape hull = entry.getValue() == active ? activeHull
				: entry.getValue().getConvexHull();
			if (hull == null)
			{
				continue;
			}

			Rectangle bounds = hull.getBounds();
			FontMetrics metrics = graphics.getFontMetrics();
			int x = bounds.x + (bounds.width - metrics.stringWidth(stage.getLabel())) / 2;
			int y = bounds.y + bounds.height / 2;

			drawLabel(graphics, x, y, stage.getLabel(),
				stage == BloodwoodStage.COLLECT ? config.collectColor() : config.chopLabelColor());
		}
	}

	private void drawLabel(Graphics2D graphics, int x, int y, String text, Color color)
	{
		graphics.setColor(SHADOW);
		graphics.drawString(text, x + 2, y + 2);
		graphics.setColor(color);
		graphics.drawString(text, x, y);
	}

	/**
	 * Full to empty, so a bar running out is obvious without reading the figure.
	 *
	 * <p>Except that a tree whose running tap will finish it is held at full colour
	 * however low the bar has fallen. The figure is read to answer one question - do I
	 * have to come back and tap this again - and once a tap in progress will clear the
	 * remainder the answer is no, so shading it like a tree that still needs work says
	 * the opposite of what is true.</p>
	 */
	private Color sapColor(boolean finalTap, double fraction)
	{
		if (finalTap)
		{
			return config.sapFullColor();
		}
		if (fraction >= 0.66)
		{
			return config.sapFullColor();
		}
		return fraction >= 0.33 ? config.sapHalfColor() : config.sapLowColor();
	}

	private void drawCombo(Graphics2D graphics, Rectangle box)
	{
		int combo = plugin.getCombo();
		if (combo < 2)
		{
			return;
		}

		String text = combo + "x";
		// Grows with the combo so a long one reads without being counted, and pulses on
		// the tick it climbs so the increment itself is visible.
		double age = client.getTickCount() - plugin.getLastJudgementTick()
			+ plugin.getTickProgress();
		double pulse = age < 0.5 ? 1 + (0.5 - age) * 0.8 : 1;
		float size = (float) (Math.min(32f, 14f + combo * 0.6f) * pulse);

		graphics.setFont(font(graphics, size));
		FontMetrics metrics = graphics.getFontMetrics();
		// Drawn on the far side of the pull-back box from the tree, so it never sits in
		// the space the mouse travels between the two targets.
		int x = box.x - 8 - metrics.stringWidth(text);
		int y = box.y + box.height / 2 + metrics.getAscent() / 2;
		graphics.setColor(SHADOW);
		graphics.drawString(text, x + 2, y + 2);
		graphics.setColor(plugin.getComboColor());
		graphics.drawString(text, x, y);
	}

}
