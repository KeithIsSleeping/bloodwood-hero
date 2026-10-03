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

import com.google.inject.Provides;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.worldhopper.ping.Ping;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;

/**
 * Turns the bloodwood chopping rhythm into a falling-note game.
 *
 * <p>Chopping a bloodwood is already a rhythm: two clicks on yourself inside one tick to
 * pull the axe back, then a single click on the tree on the tick after, twenty-two times.
 * Done right it is a metronome, and done wrong it simply stops producing chops - which is
 * feedback that arrives too late and says nothing about which half you got wrong. This
 * draws the beat in front of you instead.</p>
 *
 * <p>Nothing here is inferred from your clicks. Each tree carries a chopping-progress
 * varbit which the game advances when a chop actually lands, so that is what the beat is
 * anchored to and what the score counts. Clicks are read only to light the lane up as you
 * press, never to decide whether a chop happened.</p>
 *
 * <p>Sits alongside the Visual Bloodwood plugin rather than replacing it - that one reads
 * the same varbits for tree state, bleeding and buckets, and this adds only the rhythm.</p>
 */
@PluginDescriptor(
	name = "Bloodwood Hero",
	description = "A falling-note rhythm track for the bloodwood chopping cycle",
	tags = {"bloodwood", "woodcutting", "rhythm", "tick", "vampyrium", "sap"}
)
public class BloodwoodHeroPlugin extends Plugin
{
	/** The six choppable bloodwood trees, in the same order as their progress varbits. */
	private static final int[] BLOODWOOD_TREES = {
		ObjectID.BLOODWOOD_TREE1, ObjectID.BLOODWOOD_TREE2, ObjectID.BLOODWOOD_TREE3,
		ObjectID.BLOODWOOD_TREE4, ObjectID.BLOODWOOD_TREE5, ObjectID.BLOODWOOD_TREE6};

	private static final Set<Integer> BLOODWOOD_TREE_IDS = Set.of(
		ObjectID.BLOODWOOD_TREE1, ObjectID.BLOODWOOD_TREE2, ObjectID.BLOODWOOD_TREE3,
		ObjectID.BLOODWOOD_TREE4, ObjectID.BLOODWOOD_TREE5, ObjectID.BLOODWOOD_TREE6);

	/**
	 * One per tree, advanced by the game as a chop lands.
	 *
	 * <p>This is the only honest signal that a chop happened. An animation plays whether
	 * or not the cycle worked, and a click says only that you asked.</p>
	 */
	private static final int[] CHOPPING_PROGRESS = {
		VarbitID.BLOODWOOD_TREE_CHOPPING_PROGRESS1, VarbitID.BLOODWOOD_TREE_CHOPPING_PROGRESS2,
		VarbitID.BLOODWOOD_TREE_CHOPPING_PROGRESS3, VarbitID.BLOODWOOD_TREE_CHOPPING_PROGRESS4,
		VarbitID.BLOODWOOD_TREE_CHOPPING_PROGRESS5, VarbitID.BLOODWOOD_TREE_CHOPPING_PROGRESS6};

	/**
	 * One per tree, non-zero once it has been chopped through and started to bleed.
	 *
	 * <p>A bleeding tree is tapped for sap rather than chopped, so the cycle this draws no
	 * longer applies to it. This is what separates "still being cut" from "finished", and
	 * it is a cleaner signal than the chop count because it does not depend on knowing how
	 * many chops the tree happened to need.</p>
	 */
	private static final int[] BLEEDING_PROGRESS = {
		VarbitID.BLOODWOOD_TREE_BLEEDING_PROGRESS1, VarbitID.BLOODWOOD_TREE_BLEEDING_PROGRESS2,
		VarbitID.BLOODWOOD_TREE_BLEEDING_PROGRESS3, VarbitID.BLOODWOOD_TREE_BLEEDING_PROGRESS4,
		VarbitID.BLOODWOOD_TREE_BLEEDING_PROGRESS5, VarbitID.BLOODWOOD_TREE_BLEEDING_PROGRESS6};

	/**
	 * One per tree, describing the bucket under it: absent, filling, or full.
	 *
	 * <p>Full is what matters. Once the sap has run out the tree's next click is not a
	 * chop at all - it collects the bucket - and that click shares its menu option with
	 * chopping, so nothing in the text distinguishes them. This does.</p>
	 */
	private static final int[] BUCKET_PLACED = {
		VarbitID.BLOODWOOD_TREE_BUCKET_PLACED1, VarbitID.BLOODWOOD_TREE_BUCKET_PLACED2,
		VarbitID.BLOODWOOD_TREE_BUCKET_PLACED3, VarbitID.BLOODWOOD_TREE_BUCKET_PLACED4,
		VarbitID.BLOODWOOD_TREE_BUCKET_PLACED5, VarbitID.BLOODWOOD_TREE_BUCKET_PLACED6};

	/** The bucket value that means it is full and waiting to be collected. */
	private static final int BUCKET_FULL = 2;

	/** How far from a tree's own tiles still counts as standing at it. */
	private static final int ARRIVAL_DISTANCE = 2;

	/**
	 * How much sap one tap draws off a tree.
	 *
	 * <p>Used only to decide whether a tap being clicked will finish the tree, which is
	 * the question the sap figure exists to answer.</p>
	 */
	private static final int TAP_YIELD = 25;

	/**
	 * How many ticks one chop cycle takes: a pull-back tick, then a chop tick.
	 *
	 * <p>Fixed, because the game fixes it. Chops land every second tick without
	 * exception, so this was a setting that was correct at its default and wrong
	 * everywhere else - and wrong quietly, since a longer cycle leaves the combo test
	 * (<code>since == CYCLE_TICKS</code>) permanently unsatisfiable and the score stuck
	 * at zero with nothing on screen to explain it.</p>
	 */
	private static final int CYCLE_TICKS = 2;

	/**
	 * How far the sap bar must fall before a tree will take another tap.
	 *
	 * <p>Measured: a tap started at eighty sap took another at seventy, and one started
	 * at forty took another at thirty - ten drawn off in both cases. Ten of a tap's
	 * twenty-five is forty per cent, so the wound closes with sixty per cent of the bar
	 * still showing.</p>
	 *
	 * <p>Consistent with every refusal on record, which sat at sixty-seven per cent and
	 * above and never below. It is a property of the wound rather than of the tree, which
	 * is why the figure is the same whether the tree holds eighty sap or forty.</p>
	 */
	private static final double RETAP_BAR_THRESHOLD = 0.60;

	/**
	 * The progress a tree has to reach before it opens and starts to bleed.
	 *
	 * <p>Measured: the chopping varbit climbs in steps of roughly a hundred and seventy
	 * and resets the moment it reaches three thousand eight hundred, with the last chop
	 * clamped so it lands exactly there rather than overshooting.</p>
	 *
	 * <p>This is what makes the axe matter. The target is fixed, so an axe that adds more
	 * per chop needs fewer chops - which is the whole of the published table, from
	 * twenty-four on an adamant axe down to eighteen on a crystal felling axe, without
	 * needing the table itself.</p>
	 */
	private static final int CHOP_PROGRESS_TARGET = 3800;

	/**
	 * How far a sap bar may be from a tree and still be that tree's.
	 *
	 * <p>A bar is drawn over the tree it belongs to, so this only has to allow for the
	 * width of one. What it is really keeping out is the engorged bloodwood, which raises
	 * a bar from the same NPC id and is not a tree this plugin has anything to say
	 * about.</p>
	 */
	private static final int HEADBAR_MAX_DISTANCE = 2;

	/**
	 * How long a clicked tap has to show itself before the intention is dropped.
	 *
	 * <p>Long enough to walk the width of the clearing, short enough that a tree clicked
	 * and abandoned does not sit waiting to claim some later change as its own.</p>
	 */
	private static final int PENDING_TAP_TICKS = 15;

	/** Ticks without a chop after which the run is considered over and the track idles. */
	private static final int IDLE_TICKS = 10;

	/**
	 * How often a chop that earns no milestone remarks on what is left in the tree.
	 *
	 * <p>Low on purpose. The point is that it turns up now and then, not that it keeps a
	 * running total - said every chop it would be a progress bar, and it shares the
	 * screen with the grade for every chop already.</p>
	 */
	private static final double CHOPS_LEFT_CHANCE = 0.22;

	/**
	 * What gets shouted at each combo milestone.
	 *
	 * <p>Drawn from where the chopping actually happens rather than from woodcutting in
	 * general. A bloodwood is not felled, it is <em>wounded</em> until it bleeds sap into
	 * a bucket, and it drops no nests and no leaves - so timber, planks and leaves have
	 * nothing to do with it, and the lumberjack outfit does not even work here. The trees
	 * are native to Vampyrium, a world of blood-red skies and darkwood forests ruled by
	 * House Drakan, and the sap goes into seeking arrows.</p>
	 *
	 * <p>Grouped so the shout grows with the run: the mild ones are for first blood at
	 * ten, and the loudest are kept back for the combos that deserve them. Picked at
	 * random within a group so the same milestone does not read the same way twice.</p>
	 */
	private static final String[] SHOUTS_GOOD = {
		"FIRST BLOOD!", "TAPPED IN!", "SAP HAPPENS!", "DRAWING BLOOD!", "CHOP CHOP!"};

	private static final String[] SHOUTS_BETTER = {
		"BLOODY GOOD!", "WOUND UP!", "A FINE VINTAGE!", "BUCKET LIST!",
		"VEIN ATTEMPT? HARDLY!"};

	private static final String[] SHOUTS_GREAT = {
		"BLOOD MOON RISING!", "DARKWOOD DEVASTATION!", "BLED DRY AND LOVING IT!",
		"HOUSE DRAKAN APPROVES!", "SEEKING ARROWS INCOMING!"};

	private static final String[] SHOUTS_BEST = {
		"LORD OF THE BLOODWOOD!", "DRAKAN WOULD BE PROUD!", "THE BLOOD MOON HOWLS!",
		"VAMPYRIUM TREMBLES!", "A THIRST UNQUENCHED!"};

	private static final String[] SHOUTS_INHUMAN = {
		"INHUMAN!", "XARPUS WEPT!", "NO BUCKET CAN HOLD YOU!",
		"THE ARANEI CANNOT COUNT THIS HIGH!", "ZAROS HIMSELF IS WATCHING!"};

	/** Highest combo the multiplier climbs to, so a long run cannot run away with it. */
	private static final int MAX_MULTIPLIER = 10;

	private static final String KEY_HIGH_SCORE = "highScore";
	private static final String KEY_BEST_COMBO = "bestCombo";

	/**
	 * How often the round trip to the game server is re-measured.
	 *
	 * <p>Infrequent on purpose. The figure is only wanted while chopping, it barely moves
	 * over a session, and the client has no shared ping service - the world hopper's
	 * readings are package private, so every plugin that wants one measures its own. That
	 * makes each of them another ICMP echo to the same host, and there is no reason for
	 * this one to be frequent when a stale reading costs nothing.</p>
	 */
	private static final int PING_PERIOD_SECONDS = 30;

	/**
	 * The delay before a click reaches the wire, as a fraction of a tick.
	 *
	 * <p>A ping measures the wire and nothing else. Before a click gets onto it the click
	 * waits on the client: for the frame loop to notice the button went down, and for the
	 * next outbound packet to carry it. That wait counts against the tick exactly as the
	 * network does - the server cares when the click <em>arrives</em>, not when it was
	 * pressed - so leaving it out made the window too generous by about this much, and
	 * clicks in the last tenth of the drawn box were landing in the following tick.</p>
	 *
	 * <p>Not derived from ping, because it is not a property of the connection. It is the
	 * client's own, and it does not shrink on a better one.</p>
	 */
	private static final double INPUT_LATENCY = 0.08;

	/**
	 * The window never shrinks past this.
	 *
	 * <p>Not a guess at the window, but a floor on what is worth drawing: a connection
	 * bad enough to eat most of a tick has made the rhythm unplayable, and a sliver of a
	 * box would say that less clearly than a short one does.</p>
	 */
	private static final double MIN_WINDOW = 0.25;

	/** The game's complaint when the pull-back was too short or came too early. */
	private static final String PULL_BACK_FAILED = "pull your axe back";

	/**
	 * The game's complaint when the axe was drawn back and then held too long.
	 *
	 * <p>The other half of a failed chop, and a distinct message rather than a wording of
	 * the same one: "you can't hold your axe back for that long" is the pull-back having
	 * been done properly and the chop never coming, where {@link #PULL_BACK_FAILED} is
	 * the chop coming with no pull-back behind it. Matching only the latter left this
	 * case uncorrected.</p>
	 *
	 * <p>This is also what makes a late chop safe to accept on sight. There is a deadline
	 * on holding the axe, but the game announces it rather than leaving it to be guessed
	 * at, so the plugin can take every chop on trust and undo the ones it is told about
	 * instead of inventing a tick count and being wrong at the edges.</p>
	 *
	 * <p>Matched on the middle of the sentence to avoid the apostrophe, which the game
	 * writes as a typographic one.</p>
	 */
	private static final String HELD_TOO_LONG = "hold your axe back for that long";

	/**
	 * The game's own word that a tree is nearly out of sap.
	 *
	 * <p>Worth more than the arithmetic it replaces. The sap figure has to be compared
	 * against what one tap draws off to guess whether another will be needed; this is the
	 * game saying so outright, and it arrives whether or not the guess was close.</p>
	 *
	 * <p>Matched on "bled out", which is the distinctive part of "that tree has almost
	 * bled out, you'll need to open a new wound once it's done".</p>
	 */
	private static final String TREE_ALMOST_EMPTY = "bled out";

	/**
	 * The game's answer when a tree is not ready to be tapped again.
	 *
	 * <p>"The wound on that tree doesn't need to be reopened yet." The click did nothing,
	 * so it says nothing about the tree and must not be allowed to stand in for a tap
	 * that never happened.</p>
	 *
	 * <p>Matched on "reopened" rather than "opened". The two differ by two letters and
	 * the shorter one is not a substring of the longer, so the guess this replaces never
	 * matched the sentence it was written for and the check had never once fired.</p>
	 */
	private static final String WOUND_NOT_NEEDED = "need to be reopened";

	/**
	 * Every axe's chopping animation, which is what marks the swing.
	 *
	 * <p>The same constants the woodcutting plugin lists, repeated because its own set is
	 * package private and cannot be reached from here.</p>
	 */
	private static final Set<Integer> WOODCUTTING_ANIMS = Set.of(
		AnimationID.HUMAN_WOODCUTTING_BRONZE_AXE, AnimationID.HUMAN_WOODCUTTING_IRON_AXE,
		AnimationID.HUMAN_WOODCUTTING_STEEL_AXE, AnimationID.HUMAN_WOODCUTTING_BLACK_AXE,
		AnimationID.HUMAN_WOODCUTTING_MITHRIL_AXE, AnimationID.HUMAN_WOODCUTTING_ADAMANT_AXE,
		AnimationID.HUMAN_WOODCUTTING_RUNE_AXE, AnimationID.HUMAN_WOODCUTTING_GILDED_AXE,
		AnimationID.HUMAN_WOODCUTTING_DRAGON_AXE, AnimationID.HUMAN_WOODCUTTING_INFERNAL_AXE,
		AnimationID.HUMAN_WOODCUTTING_3A_AXE, AnimationID.HUMAN_WOODCUTTING_CRYSTAL_AXE,
		AnimationID.HUMAN_WOODCUTTING_TRAILBLAZER_AXE,
		AnimationID.HUMAN_WOODCUTTING_TRAILBLAZER_AXE_NO_INFERNAL,
		AnimationID.HUMAN_WOODCUTTING_TRAILBLAZER_RELOADED_AXE,
		AnimationID.HUMAN_WOODCUTTING_TRAILBLAZER_RELOADED_AXE_NO_INFERNAL,
		AnimationID.FORESTRY_2H_AXE_CHOPPING_BRONZE, AnimationID.FORESTRY_2H_AXE_CHOPPING_IRON,
		AnimationID.FORESTRY_2H_AXE_CHOPPING_STEEL, AnimationID.FORESTRY_2H_AXE_CHOPPING_BLACK,
		AnimationID.FORESTRY_2H_AXE_CHOPPING_MITHRIL,
		AnimationID.FORESTRY_2H_AXE_CHOPPING_ADAMANT,
		AnimationID.FORESTRY_2H_AXE_CHOPPING_RUNE, AnimationID.FORESTRY_2H_AXE_CHOPPING_DRAGON,
		AnimationID.FORESTRY_2H_AXE_CHOPPING_CRYSTAL,
		AnimationID.FORESTRY_2H_AXE_CHOPPING_CRYSTAL_INACTIVE,
		AnimationID.FORESTRY_2H_AXE_CHOPPING_3A);

	@Inject
	private Client client;

	@Inject
	private ConfigManager configManager;

	@Inject
	private BloodwoodHeroConfig config;

	@Inject
	private WorldService worldService;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BloodwoodHeroOverlay overlay;

	@Inject
	private BloodwoodHeroPanelOverlay panelOverlay;

	/**
	 * Sap left in each tree, which is the figure printed beside it.
	 *
	 * <p>How full the bar looks is a different question and is read from the bar itself
	 * rather than from this - see {@link #getBarFraction}.</p>
	 */
	private final int[] sap = new int[CHOPPING_PROGRESS.length];

	/** Last seen value of each tree's progress varbit, to spot the moment it advances. */
	private final int[] lastProgress = new int[CHOPPING_PROGRESS.length];

	/**
	 * Chops landed on each tree, counted rather than read off a varbit.
	 *
	 * <p>The chopping varbit is not a tally of chops. It is progress toward opening the
	 * tree, climbing by about a hundred and seventy a chop until it reaches
	 * {@link #CHOP_PROGRESS_TARGET} - which is why reading it as a count announced three
	 * thousand one hundred and fifty chops out of twenty-two.</p>
	 *
	 * <p>Each rise in it is one chop, so counting the rises gives the figure the varbit
	 * was wrongly being asked for.</p>
	 */
	private final int[] chopsDone = new int[CHOPPING_PROGRESS.length];

	/** Running average of what one chop adds, which is how the axe shows itself. */
	private long stepSum;
	private int stepCount;

	/**
	 * The trees in the scene, by object id.
	 *
	 * <p>Kept so the tree whose varbit just advanced can be turned into something with a
	 * position on screen. The varbits and the object ids run in the same order, so the
	 * index of the one that moved names the tree being chopped.</p>
	 */
	private final Map<Integer, GameObject> treesById = new HashMap<>();

	/**
	 * The axe clickbox that appears while chopping, which is what the pull-back is clicked
	 * on. It is an NPC rather than part of the player, so it has a clickbox of its own -
	 * and that clickbox is the target the notes have to be read against.
	 */
	@Getter
	private NPC axeClickbox;

	/** The sap bars in the scene, refreshed each tick alongside the axe clickbox. */
	private final List<NPC> headbars = new ArrayList<>();

	/**
	 * Each tree's pull-back clickbox, by tree object id.
	 *
	 * <p>The clickbox belongs to the tree it stands beside, not to the player. A pull-back
	 * is drawn at one tree and spent at that tree: you cannot draw the axe back here, walk
	 * over there and swing it. So there is one of these per tree being worked, and which
	 * one you click says which tree you are working - the same thing a click on the trunk
	 * says.</p>
	 *
	 * <p>This was previously resolved by matching the clickbox standing on the player's
	 * own tile, which found the right one only while the player stood at the tree they
	 * had last clicked. Clicking a different tree's clickbox then did nothing the plugin
	 * could see, so the track stayed on the old tree while the player was certain they
	 * had switched.</p>
	 */
	private final Map<Integer, NPC> clickboxByTree = new HashMap<>();

	/** The tree each of those bars belongs to, by the same index. */
	private final List<Integer> headbarTrees = new ArrayList<>();

	/** The tree the last chop landed on, or null before the first chop of a run. */
	@Getter
	private GameObject activeTree;

	/** Index of that tree in the varbit and object id arrays, or -1 when none is known. */
	private int activeTreeIndex = -1;

	/**
	 * Whether the player moved this tick.
	 *
	 * <p>Chopping is done standing still, so moving means going somewhere, and going
	 * somewhere means there is nothing worth drawing yet. This is what tells a walk apart
	 * from a chop without having to guess what a click meant: which tree the player is at
	 * is known exactly, and whether they are on their way elsewhere is known exactly, and
	 * neither needs the other.</p>
	 */
	private boolean moving;

	/** Where the player stood last tick, which is the only way to notice them moving. */
	private WorldPoint lastPlayerPosition;

	/**
	 * The tree the player last asked to chop, as opposed to the one they are standing at.
	 *
	 * <p>Used only to decide whether to draw, never to decide where. Those are different
	 * questions, and every attempt to answer both with one value went wrong the same way:
	 * a click says what was wanted, the position says what is true, and drawing a target
	 * from what was wanted puts it over a tree the player has not reached.</p>
	 *
	 * <p>As a gate it is safe, because its failure is silence. Asked for a tree and not
	 * there yet, nothing is drawn. Asked for a tree and then gone elsewhere, nothing is
	 * drawn until the next click says what is wanted now.</p>
	 */
	private int requestedTreeId = -1;

	/** Tick the most recent chop landed on, which is what the scoring measures against. */
	@Getter
	private int lastChopTick = -1;

	/**
	 * Tick a chop is known or assumed to have landed on, which the drawn beat hangs off.
	 *
	 * <p>Separate from the chop above so that a pull-back can start the beat before any
	 * chop has completed, without that guess being scored as a chop.</p>
	 */
	private int beatAnchorTick = -1;

	/** Tick either target was last clicked, so the boxes appear on the click rather than
	 * on the chop that follows it. */
	private int lastInteractionTick = -1;

	/**
	 * Whether the last thing this tree was asked to do was chop.
	 *
	 * <p>The only honest answer to "is this player chopping", and it has to be recorded
	 * at the click because nothing afterwards can recover it. Tapping, collecting and
	 * chopping all leave the same tree in the same state once they finish - a collected
	 * bucket is emptied before the varbits are read, so by then the tree looks exactly
	 * like one waiting to be chopped, and the collect reads as a chop.</p>
	 */
	private boolean chopIntent;

	/**
	 * Whether the track was up last tick, so that it going down can be noticed.
	 *
	 * <p>Stepping away from a tree ends the cycle: the axe is no longer drawn back, so
	 * coming back needs a fresh pair exactly as the first chop of the tree did. Without
	 * noticing the moment it ended, the old cadence was still there on return and the
	 * track picked up mid-cycle on a rhythm the player was no longer keeping.</p>
	 */
	private boolean wasChopping;

	/**
	 * Trees whose running tap was clicked as their last one, by object id.
	 *
	 * <p>Held per tree rather than for the one being worked, because the question it
	 * answers is asked of every tree on screen at once: which of these still need coming
	 * back to.</p>
	 */
	private final Set<Integer> finalTapClicked = new HashSet<>();

	/**
	 * A tap that has been clicked on a nearly-empty tree but not yet seen to happen.
	 *
	 * <p>The readings taken at the click are kept with it, since what confirms the tap is
	 * the tree having moved on from them.</p>
	 */
	private int pendingTapTree = -1;
	private int pendingTapIndex = -1;
	private int pendingTapSap;
	private int pendingTapBucket;
	private int pendingTapTick;

	/** The sap bar's reading when the pending tap was clicked, for calibrating the rule. */
	private int pendingTapBarRatio = -1;
	private int pendingTapBarScale = -1;
	private int lastTapClickTree = -1;

	/**
	 * When and where in its tick each lane was last struck.
	 *
	 * <p>Kept per lane because both can be hit in the same tick and each has its own box
	 * to flash. The fraction is where in the tick the press landed, which is where down
	 * the box the note had reached - so the flash can be put on the note that was hit
	 * rather than in the middle of the box.</p>
	 *
	 * <p>Wall clock rather than ticks, because the flash is shorter than a tick and has
	 * to fade smoothly across frames.</p>
	 */
	private long pullBackHitMillis = -1;
	private double pullBackHitAt;
	private int pullBackHitClick;
	private long chopHitMillis = -1;
	private double chopHitAt;
	private int chopHitClick;

	/**
	 * When each lane was last clicked with nothing to click.
	 *
	 * <p>Kept apart from the hits because it is the opposite event and wants the opposite
	 * answer on screen. A click with no note under it used to light up as a hit, drawn at
	 * wherever in the tick it happened to land, which read as the track flashing at
	 * random rather than as the player having struck at nothing.</p>
	 */
	private long pullBackMissMillis = -1;
	private long chopMissMillis = -1;

	/** Where the last pull-back click landed on screen, and when. */
	private int pullBackClickX;
	private int pullBackClickY;
	private long pullBackClickMillis = -1;

	/**
	 * What gets said when a chop click lands on the axe instead of the tree.
	 *
	 * <p>Every one of them names both halves of the mistake - what was hit and what was
	 * wanted - because the message has to be read at a glance mid-rhythm and a joke that
	 * leaves the player working out what happened is worse than no joke. Several of them
	 * only so the same line does not wear out over a long trip.</p>
	 */
	private static final String[] WRONG_TARGET_SHOUTS = {
		"THAT WAS THE AXE - CLICK THE TREE",
		"THE TREE! NOT THE AXE!",
		"WOOD, NOT STEEL!",
		"YOUR AXE GOT IN THE WAY",
		"SWUNG AT YOUR OWN AXE",
		"AIM PAST THE AXE"};

	/** The line picked for the last wrong-target click, held while it is on screen. */
	private String wrongTargetShout;

	/** The last one is excluded, so the same line never lands twice running. */
	private String pickWrongTarget()
	{
		String choice;
		do
		{
			choice = WRONG_TARGET_SHOUTS[random.nextInt(WRONG_TARGET_SHOUTS.length)];
		}
		while (choice.equals(wrongTargetShout));
		return choice;
	}

	/** The line to show for the last wrong-target click. */
	public String getWrongTargetShout()
	{
		return wrongTargetShout == null ? WRONG_TARGET_SHOUTS[0] : wrongTargetShout;
	}

	/**
	 * Whether the last pull-back click landed inside the chop target.
	 *
	 * <p>Which means the player was aiming at the tree and hit the axe or their own
	 * character instead. The two overlap while the axe swings across the trunk, and the
	 * result - a pull-back when a chop was wanted - looks like the game ignoring the
	 * click rather than like a different thing being clicked.</p>
	 */
	public boolean pullBackHitChopBox(java.awt.Rectangle chopBox, long withinMillis)
	{
		return chopBox != null && pullBackClickMillis > 0
			&& System.currentTimeMillis() - pullBackClickMillis <= withinMillis
			&& chopBox.contains(pullBackClickX, pullBackClickY);
	}

	// Wall-clock start of the current tick, and how long the last one ran for.
	private long lastTickMillis;
	private long measuredTickMillis = 600;

	/**
	 * The shout for the chop just landed, held until the next one replaces it.
	 *
	 * <p>Latched rather than worked out on demand, because it is chosen at random and the
	 * overlay asks for it every frame.</p>
	 */
	@Getter
	private String callout;

	/** The last shout given, so the next one can avoid repeating it. */
	private String lastShout;

	private final Random random = new Random();

	@Getter
	private int combo;

	@Getter
	private int bestCombo;

	@Getter
	private int totalChops;

	/** Session score, and the best ever recorded, which survives logging out. */
	@Getter
	private long score;

	@Getter
	private long highScore;

	/** Whether a record has moved since it was last written out. */
	private boolean recordsDirty;

	/** How the last chop was graded, and when, so the call-out can fade on its own. */
	@Getter
	private BloodwoodJudgement lastJudgement;

	@Getter
	private int lastJudgementTick = -1;

	/**
	 * Where in its tick the last pull-back click fell, this tick and the one before.
	 *
	 * <p>A pull-back is judged a tick later - by the chop it produces or by the game
	 * complaining it was short - so the figure has to outlive the tick it was measured
	 * in.</p>
	 */
	private double pullBackFraction = -1;
	private double pullBackFractionLastTick = -1;

	/**
	 * The round trip to the game server, in milliseconds, or -1 before it is known.
	 *
	 * <p>Written by a background task and read while drawing, so it is volatile.</p>
	 */
	private volatile int pingMillis = -1;

	/**
	 * Whether there are bloodwoods in the scene, for the ping thread to read.
	 *
	 * <p>Written on the client thread and read on the ping thread, so it is volatile. It
	 * exists because the ping thread cannot ask the client directly: deciding whether to
	 * measure means knowing what is in the scene, and the scene is not safe to read from
	 * off the client thread.</p>
	 */
	private volatile boolean atBloodwoods;

	/**
	 * The plugin's own thread for measuring the round trip.
	 *
	 * <p>Its own, rather than the client's shared scheduler, because a ping blocks: name
	 * resolution is unbounded and the ICMP and TCP attempts each wait up to two seconds.
	 * The shared scheduler is a single thread used by the whole client, so parking it for
	 * two seconds out of every few would hold up every other plugin's background work.
	 * The world hopper keeps its own for exactly this reason.</p>
	 */
	private ScheduledExecutorService pingExecutor;

	private ScheduledFuture<?> pingTask;

	/** Tick the axe last visibly swung, which is what the effects are timed from. */
	@Getter
	private int chopAnimTick = -1;

	/**
	 * Whether the next chop is re-establishing the beat rather than keeping it.
	 *
	 * <p>Set whenever something happened that is not part of the rhythm - a different
	 * tree, a break long enough that the track went away - so that the chop which brings
	 * the player back in is not scored against a beat they were not in a position to
	 * keep.</p>
	 */
	private boolean resuming = true;

	/**
	 * Where in its tick the chop click fell, as a distance from the middle of the window.
	 *
	 * <p>Captured at click time because by the time the chop lands the tick has moved on
	 * and the information is gone.</p>
	 */
	private double pendingClickOffset;


	/** Clicks made this tick, by what they were aimed at. */
	private int selfClicksThisTick;
	private int treeClicksThisTick;

	/**
	 * Notes struck this tick, as opposed to clicks made.
	 *
	 * <p>What says which notes are gone. A click that hit nothing takes no note with
	 * it.</p>
	 */
	private int selfHitsThisTick;
	private int treeHitsThisTick;

	@Getter
	private int selfClicksLastTick;

	@Getter
	private int treeClicksLastTick;

	@Provides
	BloodwoodHeroConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BloodwoodHeroConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(panelOverlay);
		reset();
		loadRecords();

		// The window is only as long as the trip to the server leaves it, so that trip is
		// re-measured while the plugin runs rather than read once and trusted.
		pingExecutor = Executors.newSingleThreadScheduledExecutor();
		pingTask = pingExecutor.scheduleWithFixedDelay(
			this::measurePing, 0, PING_PERIOD_SECONDS, TimeUnit.SECONDS);
	}

	/** Records are per profile and survive a logout, so they are read back on start. */
	private void loadRecords()
	{
		Long storedScore = configManager.getConfiguration(
			BloodwoodHeroConfig.GROUP, KEY_HIGH_SCORE, Long.class);
		highScore = storedScore == null ? 0 : storedScore;

		Integer storedCombo = configManager.getConfiguration(
			BloodwoodHeroConfig.GROUP, KEY_BEST_COMBO, Integer.class);
		bestCombo = storedCombo == null ? 0 : storedCombo;
	}

	/**
	 * Notes that a record has moved, without writing it out.
	 *
	 * <p>During a record-setting run almost every chop beats the previous best, and
	 * writing each one is more expensive than it looks: a configuration write invalidates
	 * the client's shared settings cache and tells every subscriber in the client that
	 * something changed, several dozen of them, two or three times a second.</p>
	 *
	 * <p>Nothing reads these from the configuration while the plugin is running - they
	 * are held in fields - so the only thing a write buys is surviving a crash. Flushing
	 * when the run ends, on logout, and on shutdown gives that up only for a run that
	 * ends in a crash, which is a fair trade for the noise.</p>
	 */
	private void markRecords()
	{
		if (score > highScore)
		{
			highScore = score;
			recordsDirty = true;
		}
		if (combo > bestCombo)
		{
			bestCombo = combo;
			recordsDirty = true;
		}
	}

	/** Writes the records out, if any have moved since the last time. */
	private void flushRecords()
	{
		if (!recordsDirty)
		{
			return;
		}
		recordsDirty = false;
		configManager.setConfiguration(
			BloodwoodHeroConfig.GROUP, KEY_HIGH_SCORE, highScore);
		configManager.setConfiguration(
			BloodwoodHeroConfig.GROUP, KEY_BEST_COMBO, bestCombo);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(panelOverlay);
		flushRecords();
		if (pingTask != null)
		{
			pingTask.cancel(true);
			pingTask = null;
		}
		if (pingExecutor != null)
		{
			pingExecutor.shutdownNow();
			pingExecutor = null;
		}
		pingMillis = -1;
		reset();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// A scene reload re-reads every varbit, which would otherwise read as a flurry of
		// chops on the first tick back.
		if (event.getGameState() == GameState.LOADING
			|| event.getGameState() == GameState.HOPPING
			|| event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// Leaving is the other moment a record is worth keeping, since the run that
			// set it has certainly ended.
			flushRecords();
			reset();
		}
	}

	/**
	 * The axe clickbox belonging to <em>this</em> player, re-found every tick.
	 *
	 * <p>Every player chopping has one, so the scene holds as many of these as there are
	 * choppers and the one that happened to spawn last is almost never yours. It is
	 * identified by position: the clickbox stands on the same tile as its owner, so the
	 * one matching the local player's tile is the one to draw against.</p>
	 *
	 * <p>Re-found rather than cached, because the reference goes stale every time it
	 * despawns between trees and a cached one would never be replaced.</p>
	 */
	private void updateAxeClickbox()
	{
		axeClickbox = null;
		clickboxByTree.clear();
		headbars.clear();
		headbarTrees.clear();

		Player local = client.getLocalPlayer();
		WorldView wv = client.getTopLevelWorldView();
		if (local == null || wv == null)
		{
			return;
		}

		WorldPoint here = local.getWorldLocation();

		for (NPC npc : wv.npcs())
		{
			// The bar over a bleeding tree is an NPC of its own, which is what makes the
			// sap figure placeable against it rather than against the tree's clickbox.
			if (npc.getId() == NpcID.BLOODWOOD_HEADBAR_NPC)
			{
				headbars.add(npc);
				// Tied to its tree here, once a tick, rather than every time the figure
				// is drawn. The search scans every tree in the clearing, and the answer
				// cannot change between ticks - neither bars nor trees move.
				headbarTrees.add(nearestTreeId(npc));
			}
			else if (npc.getId() == NpcID.PLAYER_AXE_CLICKBOX_VIS)
			{
				// Tied to the tree it stands beside rather than to whoever is swinging
				// it. Bounded the same way the sap bars are: one further off than a tree
				// is wide belongs to a tree of its own, or to nothing of ours.
				int treeId = nearestTreeId(npc);
				if (treeId > 0)
				{
					clickboxByTree.put(treeId, npc);
				}
			}
		}
	}

	/** The sap bars currently in the scene, one per bleeding tree. */
	public List<NPC> getHeadbars()
	{
		return headbars;
	}

	/** Each tree's pull-back clickbox, by tree object id. */
	public Map<Integer, NPC> getClickboxByTree()
	{
		return clickboxByTree;
	}

	/** The trees in the scene, by object id. */
	public Map<Integer, GameObject> getTreesById()
	{
		return treesById;
	}

	/**
	 * The tree a given sap bar belongs to.
	 *
	 * <p>Read from the association worked out when the bars were collected for this tick.
	 * The bar carries no reference to its tree, and with six of them in a clearing the
	 * only thing tying the two together is position - so finding it means scanning every
	 * tree, which is not something to do once per bar per frame.</p>
	 */
	public int getTreeIdForHeadbar(NPC headbar)
	{
		int index = headbars.indexOf(headbar);
		return index < 0 || index >= headbarTrees.size() ? -1 : headbarTrees.get(index);
	}

	/**
	 * The tree a sap bar is standing on, or -1 if it is not standing on one of ours.
	 *
	 * <p>Bounded by distance rather than taking whichever tree is nearest. A bar belongs
	 * to the tree it is drawn over, so one further away than a tree is wide belongs to
	 * something else - and the engorged bloodwood has a bar of its own, drawn by the same
	 * NPC id. Unbounded, that bar was being handed the sap figure of whichever real
	 * bloodwood happened to be closest, and a tree nobody can chop in rhythm was being
	 * given a counter telling them when to.</p>
	 */
	private int nearestTreeId(NPC headbar)
	{
		WorldPoint at = headbar.getWorldLocation();
		if (at == null)
		{
			return -1;
		}

		int bestId = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (Map.Entry<Integer, GameObject> entry : treesById.entrySet())
		{
			WorldPoint tree = entry.getValue().getWorldLocation();
			if (tree == null || tree.getPlane() != at.getPlane())
			{
				continue;
			}
			int distance = Math.max(Math.abs(tree.getX() - at.getX()),
				Math.abs(tree.getY() - at.getY()));
			if (distance < bestDistance)
			{
				bestDistance = distance;
				bestId = entry.getKey();
			}
		}
		return bestDistance <= HEADBAR_MAX_DISTANCE ? bestId : -1;
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		GameObject object = event.getGameObject();
		if (BLOODWOOD_TREE_IDS.contains(object.getId()))
		{
			treesById.put(object.getId(), object);
		}
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		GameObject object = event.getGameObject();
		if (treesById.get(object.getId()) == object)
		{
			treesById.remove(object.getId());
			if (activeTree == object)
			{
				activeTree = null;
			}
		}
	}

	private void reset()
	{
		for (int i = 0; i < lastProgress.length; i++)
		{
			lastProgress[i] = -1;
		}
		lastChopTick = -1;
		beatAnchorTick = -1;
		lastInteractionTick = -1;
		chopIntent = false;
		wasChopping = false;
		requestedTreeId = -1;
		moving = false;
		lastPlayerPosition = null;
		finalTapClicked.clear();
		pendingTapTree = -1;
		pullBackHitMillis = -1;
		chopHitMillis = -1;
		pullBackMissMillis = -1;
		chopMissMillis = -1;
		combo = 0;
		selfClicksThisTick = 0;
		treeClicksThisTick = 0;
		selfHitsThisTick = 0;
		treeHitsThisTick = 0;
		selfClicksLastTick = 0;
		treeClicksLastTick = 0;
		treesById.clear();
		axeClickbox = null;
		activeTree = null;
		activeTreeIndex = -1;
		atBloodwoods = false;
		lastJudgement = null;
		lastJudgementTick = -1;
		callout = null;
		lastShout = null;
		pendingClickOffset = 0;
		pullBackFraction = -1;
		pullBackFractionLastTick = -1;
		chopAnimTick = -1;
		resuming = true;
		lastTickMillis = 0;
		measuredTickMillis = 600;
		// pingMillis is deliberately not cleared: the distance to the server is a
		// property of the connection, and a scene reload is no reason to re-measure it.
	}


	/**
	 * Colour for a combo, which climbs through tiers as it grows.
	 *
	 * <p>Kept here rather than in the overlay so the panel and the call-out cannot drift
	 * apart on what a given combo is worth.</p>
	 */
	public Color getComboColor()
	{
		if (combo >= 50)
		{
			return new Color(255, 120, 255);
		}
		if (combo >= 30)
		{
			return new Color(120, 255, 255);
		}
		if (combo >= 20)
		{
			return new Color(255, 90, 90);
		}
		if (combo >= 10)
		{
			return new Color(255, 160, 60);
		}
		return combo >= 5 ? new Color(255, 230, 110) : Color.WHITE;
	}

	/**
	 * How far into the current tree the wound is, read from the game.
	 *
	 * <p>Taken from the tree's own progress rather than from chops counted here. A count
	 * kept by this plugin starts at nothing every time the active tree changes, which is
	 * right for a combo - the rhythm is yours and starts again when you walk away - but
	 * wrong for the tree, which does not forget what has already been done to it. Coming
	 * back to a tree nineteen chops in and being told nineteen remained was that count
	 * being asked a question it could not answer.</p>
	 */
	public int getTreeChops()
	{
		return activeTreeIndex < 0 ? 0 : chopsDone[activeTreeIndex];
	}

	/**
	 * How many chops this tree still needs, worked out from the progress left to make.
	 *
	 * <p>The target is fixed and one chop is worth whatever this axe is worth, so the
	 * remainder divides out - and because the step is measured rather than assumed, this
	 * is right for any axe including the felling variants whose figures are not
	 * published.</p>
	 */
	public int getChopsLeft()
	{
		if (activeTreeIndex < 0 || stepCount == 0)
		{
			return 0;
		}

		int remaining = CHOP_PROGRESS_TARGET
			- client.getVarbitValue(CHOPPING_PROGRESS[activeTreeIndex]);
		int step = (int) (stepSum / stepCount);
		return remaining <= 0 || step <= 0 ? 0 : (remaining + step - 1) / step;
	}

	/**
	 * How many chops a tree takes with the axe in hand.
	 *
	 * <p>Counted and calculated rather than configured: what has been done plus what is
	 * left. The configured figure stands in only until a chop has been seen, since
	 * nothing is known about the axe before then.</p>
	 */
	public int getChopsPerTree()
	{
		int total = getTreeChops() + getChopsLeft();
		return total > 0 ? total : config.chopsPerTree();
	}

	/**
	 * Picks what to shout for the chop just landed, or null to stay quiet.
	 *
	 * <p>Decided here rather than in the overlay because it is random, and the overlay
	 * draws many times a tick - rolling there would reroll the shout every frame and
	 * flicker through the whole list in the time it was meant to be showing one of
	 * them.</p>
	 *
	 * <p>A combo milestone always takes precedence: it is the rarer event and the one
	 * actually being celebrated. Only when there is no milestone does the count of what
	 * is left in the tree get a turn, and then only sometimes - said every chop it would
	 * be a progress bar rather than a remark, and would drown out the grades it shares
	 * the screen with.</p>
	 */
	private String chooseCallout()
	{
		String milestone = milestoneShout();
		if (milestone != null)
		{
			return milestone;
		}

		int left = getChopsLeft();
		if (left <= 0 || random.nextDouble() >= CHOPS_LEFT_CHANCE)
		{
			return null;
		}
		// The last one says what is about to happen rather than counting to it: once the
		// tree is fully wounded it starts bleeding into the bucket, which is the thing
		// the count was leading up to.
		return left == 1 ? "ONE MORE AND IT BLEEDS!" : left + " CHOPS LEFT!";
	}

	/** A shout for the combos worth shouting about, or null for the rest. */
	private String milestoneShout()
	{
		if (combo > 0 && combo % 100 == 0)
		{
			return pick(SHOUTS_INHUMAN);
		}
		switch (combo)
		{
			case 10:
				return pick(SHOUTS_GOOD);
			case 20:
				return pick(SHOUTS_BETTER);
			case 30:
				return pick(SHOUTS_GREAT);
			case 50:
				return pick(SHOUTS_BEST);
			default:
				return null;
		}
	}

	/**
	 * One of a set, never the same one twice running.
	 *
	 * <p>Repeating immediately is what makes a random line feel scripted, and these are
	 * seen often enough for it to be noticed.</p>
	 */
	private String pick(String[] pool)
	{
		if (pool.length < 2)
		{
			return pool[0];
		}

		String choice;
		do
		{
			choice = pool[random.nextInt(pool.length)];
		}
		while (choice.equals(lastShout));

		lastShout = choice;
		return choice;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (isPullBackClick(event))
		{
			// Where the click actually landed. A pull-back inside the chop box is the
			// player aiming at the wood and hitting the axe or their own character
			// instead - the two overlap, and nothing on screen explained the result.
			net.runelite.api.Point mouse = client.getMouseCanvasPosition();
			if (mouse != null)
			{
				pullBackClickX = mouse.getX();
				pullBackClickY = mouse.getY();
				pullBackClickMillis = System.currentTimeMillis();
				// Chosen here, on the click, and not in the overlay: the overlay draws
				// many times a tick, and rolling there would run through the whole list
				// in the time one of them was meant to be showing.
				wrongTargetShout = pickWrongTarget();
			}

			selfClicksThisTick++;
			double at = clickFraction();
			// The FIRST click of the tick, because that is the one the window constrains:
			// the pull-back needs two inside one tick, so what matters is whether the first
			// left room for the second. Recording the second measured the wrong one.
			if (pullBackFraction < 0)
			{
				pullBackFraction = at;
			}

			// The note this click is reaching for is the next one not already taken,
			// which is counted in hits rather than in clicks. A click that strikes
			// nothing must not move that on: the note it failed to hit is still there,
			// still falling, and still the one the next click is aimed at.
			int note = selfHitsThisTick;

			// A click only strikes something if the tick had a note left for it. The lane
			// has to be this tick's, and the beat has to still be asking for that note -
			// a third stab at a pull-back that wants two is swinging at nothing, however
			// well timed it is.
			//
			// Before a beat exists the lane is pull-back on every tick, because that is
			// what the overlay is drawing: two notes falling every tick, since the next
			// thing the tree needs is a pull-back pair whenever the player gets to it.
			// Grading has to agree with drawing or the two contradict each other - and
			// reading the lane alone here deadlocked, since a missing beat made every
			// primer click a miss, and only a hit pair can supply the missing beat.
			boolean pullBackLane = !hasBeat()
				|| beatAt(client.getTickCount()) == BloodwoodBeat.PULL_BACK;
			boolean onNote = pullBackLane && note < BloodwoodBeat.PULL_BACK.getClicks();

			// And it has to be inside the window. One made past the edge is the click the
			// game is about to ignore, and congratulating it would be the overlay
			// disagreeing with the game.
			//
			// Measured against that note rather than the first of the tick. The window is
			// shortened by exactly the spacing so the second click has somewhere to land,
			// so testing the second against the unshifted window rejects it over the very
			// room that shortening reserved - while the overlay, which does offset by the
			// note index, draws it inside the box.
			boolean inWindow = at - note * BloodwoodBeat.CLICK_SPACING <= effectiveWindow();

			if (onNote && inWindow)
			{
				pullBackHitMillis = System.currentTimeMillis();
				pullBackHitAt = burstFraction(at);
				// Which note this click took. They are spaced apart rather than stacked,
				// so a burst that did not know which one it belonged to drew every click
				// on the first note's line.
				pullBackHitClick = note;
				selfHitsThisTick++;

				// The pair completing settles the cadence without having to wait for the
				// chop to land. Two pull-backs inside a tick means the chop is the next
				// tick - that is the rule the game plays by, not a guess about it - so
				// the beat can be set from here and the chop note starts falling at once.
				//
				// Only when there is no beat yet. Mid-run the cadence is already known,
				// and re-stating it on every pair would move the anchor about for no
				// gain, which is what used to send the notes jumping.
				if (!hasBeat() && selfHitsThisTick == BloodwoodBeat.PULL_BACK.getClicks())
				{
					beatAnchorTick = client.getTickCount() - CYCLE_TICKS + 1;
				}
			}
			else
			{
				pullBackMissMillis = System.currentTimeMillis();
			}
			lastInteractionTick = client.getTickCount();
		}
		else if (isBloodwoodClick(event))
		{
			// Every click on a bloodwood points the plugin at that tree, whatever it was
			// for. Tap and collect are not chops, but they do say which tree is being
			// worked - and knowing that is what lets the stage gate drop the track
			// immediately rather than leaving it up until the idle timer runs out.

			// Whether this is the tap that finishes the tree can only be known here: the
			// option says a tap was asked for, and the sap figure still says how much was
			// left when it was. A moment later both have moved on.
			noteTapClick(event);

			// A full bucket is collected by the same menu option that chops, and tapping
			// is a different option on the same tree. Neither starts a beat: doing so
			// would raise the track over a cycle that is not happening and wait for a
			// chop that cannot come. Recording which was asked for is the only chance to
			// tell them apart - once the action finishes the tree looks the same either
			// way, so a collect counted as a chop and put the track back up.
			chopIntent = isChopOption(event) && !isCollectReady(event.getId());

			// Which tree was clicked, whatever the click was for. Every click on a
			// bloodwood names one exactly, and that is a fact worth keeping whether it
			// asked to chop, to tap or to collect.
			//
			// It used to be cleared for anything but a chop, which threw the one certain
			// thing away and left the plugin guessing the tree from where the player was
			// stood - and a guess has to be wrong sometimes. Collecting is the case that
			// exposed it: it shares its menu option with chopping, so the request was
			// dropped, and the chopping that carried straight on afterwards had nothing
			// left naming the tree.
			//
			// Whether to draw is a separate question with its own answers: chopIntent
			// below says whether this click was a chop, and the stage check in
			// isChopping() says whether the tree wants chopping at all. Neither needs the
			// identity blurred to do its job.
			boolean switchedTree = event.getId() != requestedTreeId;
			requestedTreeId = event.getId();

			// Worked out now rather than on the next tick. The overlay draws many times
			// between ticks, so leaving it until then meant every one of those frames
			// drew the chop box on the tree this click just replaced.
			resolveActiveTree();

			if (!chopIntent)
			{
				return;
			}

			treeClicksThisTick++;
			int tick = client.getTickCount();
			lastInteractionTick = tick;

			// The track waits for a chop rather than guessing the beat from this click.
			// A click only says a chop was asked for, not which tick it will land on, and
			// a guess that turns out wrong has to correct itself the moment the real one
			// arrives - which moves every note on screen at once. Nothing is drawn until
			// the tree says a chop happened, and from then on the beat is only ever told
			// what already did happen.
			if (switchedTree)
			{
				beatAnchorTick = -1;
			}

			// Where in the tick the click fell, as a position within the part of the tick
			// that actually registers rather than within the whole of it: 0 at the middle
			// of that window and half a window at either edge.
			double window = effectiveWindow();
			double chopAt = clickFraction();
			pendingClickOffset = (chopAt - window / 2) / window;

			int note = treeHitsThisTick;
			boolean onNote = beatAt(tick) == BloodwoodBeat.CHOP
				&& note < BloodwoodBeat.CHOP.getClicks();

			// No window on the chop, unlike the pull-back. The window exists because two
			// pull-backs have to share one tick, so the first has to leave room for the
			// second; a chop is a single click with nothing to make room for. Landing it
			// late does not fail - the chop simply happens on the next tick instead, and
			// the game takes it.
			//
			// So a late one is a hit, and the beat re-anchors to wherever the chop
			// actually lands. Calling it a miss was the overlay marking the player wrong
			// about something the game had just accepted, and then holding a cycle the
			// player was no longer on.
			if (onNote)
			{
				chopHitMillis = System.currentTimeMillis();
				chopHitAt = burstFraction(chopAt);
				chopHitClick = note;
				treeHitsThisTick++;
			}
			else
			{
				chopMissMillis = System.currentTimeMillis();
			}
		}
	}

	/**
	 * Where down the box to put the burst for a click pressed at the given fraction.
	 *
	 * <p>The press is the right moment to <em>grade</em>, but the wrong place to draw.
	 * The burst cannot appear until the click has been handled, and by then the note has
	 * travelled on - so a burst drawn at the press sat visibly behind the note it was
	 * meant to be striking.</p>
	 *
	 * <p>The later of the two is therefore used: where the note has reached now, which is
	 * where it will be when the burst first appears, and where it is about to vanish
	 * from. Falling back to the press covers the case where the handling has already
	 * crossed into the next tick and the reading would be meaningless.</p>
	 */
	private double burstFraction(double pressedAt)
	{
		long since = System.currentTimeMillis() - lastTickMillis;
		if (since < 0 || since > tickLengthMillis())
		{
			return pressedAt;
		}
		return Math.max(pressedAt, since / (double) tickLengthMillis());
	}

	/**
	 * How many of this tick's notes have already been struck.
	 *
	 * <p>Counted in hits rather than in clicks. Clicks include the ones that struck
	 * nothing, and a note that was missed has not been taken - it is still falling, and
	 * still the one the next click is aimed at. Consuming it anyway made a stray click
	 * look as though it had moved the remaining note, because what was left was drawn at
	 * the second note's offset instead of the first's.</p>
	 */
	public int getHitsThisTick(BloodwoodBeat beat)
	{
		return beat == BloodwoodBeat.CHOP ? treeHitsThisTick : selfHitsThisTick;
	}

	/**
	 * How long ago a lane was struck, in ticks, or -1 if it has not been struck.
	 *
	 * <p>Measured against the tick's own length so the flash keeps pace with the notes
	 * rather than running at its own speed when the server is slow.</p>
	 */
	public double hitAge(BloodwoodBeat beat)
	{
		long at = beat == BloodwoodBeat.CHOP ? chopHitMillis : pullBackHitMillis;
		if (at < 0)
		{
			return -1;
		}
		return (System.currentTimeMillis() - at) / (double) tickLengthMillis();
	}

	/** Where down its box the note being struck had reached, 0 at the top. */
	public double hitFraction(BloodwoodBeat beat)
	{
		return beat == BloodwoodBeat.CHOP ? chopHitAt : pullBackHitAt;
	}

	/** Which of the tick's notes was struck, since they do not fall on the same line. */
	public int hitClick(BloodwoodBeat beat)
	{
		return beat == BloodwoodBeat.CHOP ? chopHitClick : pullBackHitClick;
	}

	/** How long ago a lane was clicked with nothing to click, in ticks, or -1. */
	public double missAge(BloodwoodBeat beat)
	{
		long at = beat == BloodwoodBeat.CHOP ? chopMissMillis : pullBackMissMillis;
		if (at < 0)
		{
			return -1;
		}
		return (System.currentTimeMillis() - at) / (double) tickLengthMillis();
	}

	/**
	 * Where in the current tick the mouse was actually pressed.
	 *
	 * <p>Not where the click was <em>handled</em>. The menu event arrives when the client
	 * runs the action, which happens at much the same point in every tick regardless of
	 * when the button went down - so grading from it scored a near-constant moment, and
	 * everything past the target line came out perfect while clicks on the line did not.
	 * The press carries its own timestamp, which is the thing being aimed.</p>
	 *
	 * <p>Measured against the length of the last tick rather than a flat 600ms, so server
	 * jitter moves the whole window rather than skewing the grade inside it. A press
	 * timed before this tick began was made in the previous one and is folded back into
	 * it, since that is the tick it will be judged against.</p>
	 */
	private double clickFraction()
	{
		long pressed = client.getMouseLastPressedMillis();
		if (pressed <= 0 || lastTickMillis <= 0)
		{
			return 0.5;
		}

		double delta = (pressed - lastTickMillis) / (double) tickLengthMillis();
		while (delta < 0)
		{
			delta += 1;
		}
		return Math.max(0, Math.min(1, delta));
	}

	/** The last tick's measured length, held to a sane range in case a tick was missed. */
	private long tickLengthMillis()
	{
		return Math.max(400, Math.min(900, measuredTickMillis));
	}

	/**
	 * Whether a click was the axe pull-back.
	 *
	 * <p>The clickbox is an NPC rather than part of the player, so this arrives as an NPC
	 * menu action and not as anything aimed at your character.</p>
	 *
	 * <p>Matched against the NPC itself rather than against the event's identifier. For
	 * an NPC action that identifier is the NPC's <em>index</em> in the world view, not
	 * its id, so comparing it to an id could never be true - the test was dead and every
	 * pull-back was being recognised by its option text alone. That text is on every
	 * chopper's clickbox, so in a clearing with company the plugin was counting other
	 * people's clicks as yours: consuming your notes, firing your bursts and filling the
	 * tick's click count with clicks you never made.</p>
	 *
	 * <p>Comparing against the tracked clickboxes settles both at once, since those were
	 * already resolved by the tree each stands beside.</p>
	 */
	private boolean isPullBackClick(MenuOptionClicked event)
	{
		if (!isNpcAction(event.getMenuAction()))
		{
			return false;
		}

		NPC npc = event.getMenuEntry().getNpc();
		if (npc == null)
		{
			return false;
		}

		// Which tree's clickbox this is. Clicking one is as much a statement of the tree
		// being worked as clicking the trunk, because a pull-back is spent at the tree it
		// was drawn at - there is no carrying it to another one. So a click on a different
		// tree's clickbox switches to that tree rather than being ignored, which is what
		// used to happen: the clickbox was identified by standing on the player's tile, so
		// another tree's registered as nothing at all and the track stayed where it was
		// while the player was certain they had moved it.
		for (Map.Entry<Integer, NPC> entry : clickboxByTree.entrySet())
		{
			if (entry.getValue() != npc)
			{
				continue;
			}

			int treeId = entry.getKey();
			if (treeId != requestedTreeId)
			{
				requestedTreeId = treeId;
				beatAnchorTick = -1;
				resolveActiveTree();
			}
			lastInteractionTick = client.getTickCount();
			return true;
		}

		return false;
	}

	/**
	 * Any click on a bloodwood, whatever it was asking the tree to do.
	 *
	 * <p>Gated on the action being aimed at a game object before the identifier is read,
	 * because the identifier only means an object id for object actions. A ground item
	 * puts its <em>item</em> id there, and the two sets collide exactly - bloodwood tree
	 * 33393 is also a gem sack, 33396 is incendiary data - so taking such an item off the
	 * floor was switching the active tree, resetting the chop count and dropping the
	 * track in the middle of a run.</p>
	 *
	 * <p>Matched on the six ids and nothing else. There used to be a fallback onto any
	 * target whose name contained "bloodwood", which sounds harmless and is not: the
	 * engorged bloodwood tree is also called one. That tree is the slow alternative, cut
	 * like ordinary wood with no axe to pull back and no cycle to keep, and the fallback
	 * was raising the whole track over it. The ids are generated constants covering every
	 * tree this plugin is for, so the fallback was adding a way to be wrong rather than a
	 * way to cope.</p>
	 */
	private boolean isBloodwoodClick(MenuOptionClicked event)
	{
		return isObjectAction(event.getMenuAction())
			&& BLOODWOOD_TREE_IDS.contains(event.getId());
	}

	/** Whether that click was asking to tap the tree for sap. */
	private boolean isTapOption(MenuOptionClicked event)
	{
		return Text.removeTags(event.getMenuOption()).toLowerCase().startsWith("tap");
	}

	/**
	 * Whether that click was asking to chop rather than to tap.
	 *
	 * <p>Decided by the option text rather than by the menu slot it occupies. Tap and chop
	 * sit on the same tree and move between slots depending on what it is holding, so the
	 * slot says nothing reliable while the word says exactly what was asked for.</p>
	 */
	private boolean isChopOption(MenuOptionClicked event)
	{
		return Text.removeTags(event.getMenuOption()).toLowerCase().startsWith("chop");
	}

	private static boolean isNpcAction(MenuAction action)
	{
		switch (action)
		{
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Whether a click was aimed at scenery, which is the only case an object id means
	 * anything.
	 */
	private static boolean isObjectAction(MenuAction action)
	{
		switch (action)
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Writes out what the plugin believes about every tree, so a wrong label can be
	 * traced back to the reading it was built from.
	 *
	 * <p>Both halves are here on purpose. The varbits say what each tree is, and the bar
	 * matching says which tree a figure was drawn over. A label on the wrong tree and a
	 * label reading the wrong thing look identical on screen and have nothing in common
	 * underneath, and only one line showing both can tell them apart.</p>
	 */
	/**
	 * Drops references to NPCs the moment they leave, rather than at the next tick.
	 *
	 * <p>Both of these are found once a tick and drawn from every frame, which is fifty
	 * times in between. A despawned NPC still answers for its convex hull, so for the
	 * rest of the tick the pull-back box carried on being drawn around a clickbox that
	 * had gone - at the tree it belonged to, while the player was walking to another one.
	 * That is the overlay briefly pointing at the wrong place, which is worse than it
	 * briefly pointing nowhere.</p>
	 *
	 * <p>Nothing has to be re-found here. The tick that follows looks for both again
	 * anyway; this only stops the gap being filled with something untrue.</p>
	 */
	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();

		if (npc == axeClickbox)
		{
			axeClickbox = null;
		}

		int index = headbars.indexOf(npc);
		if (index >= 0)
		{
			headbars.remove(index);
			if (index < headbarTrees.size())
			{
				headbarTrees.remove(index);
			}
		}
	}

	/**
	 * Works out which tree the player is actually at, once a tick.
	 *
	 * <p>Taken from where they are standing rather than from what they last clicked. A
	 * click is a request, and everything that followed from treating it as an answer went
	 * wrong in the same way: the clicked tree could be across the clearing, could be
	 * walked away from without another click, could be abandoned halfway, and the track
	 * carried on being drawn over it regardless. Patching that meant guessing when the
	 * request had been fulfilled and when it had been given up on, and there is no
	 * reliable signal for either.</p>
	 *
	 * <p>Where the player is standing needs no such guessing. Chopping is done from
	 * against the tree, so the tree they are against is the one they are chopping, and if
	 * they are against none of them they are chopping none of them. It cannot point at a
	 * tree they are not at, which is the only thing that kept going wrong.</p>
	 *
	 * <p>The click still decides whether they asked to chop or to tap. That is a question
	 * about intent, which only the click can answer.</p>
	 */
	private void updateActiveTree()
	{
		Player local = client.getLocalPlayer();
		LocalPoint at = local == null ? null : local.getLocalLocation();

		// Noticed here rather than anywhere else because this runs once a tick, and a
		// position compared between frames would say "moving" at every step of a walk and
		// "still" in between.
		//
		// Asked of the walk itself rather than worked out from where the player was last
		// tick. A path has stationary ticks in it, and on one of those a comparison of
		// tiles says "arrived" in the middle of a walk - which is what put the box back
		// on the tree being walked away from, for the one tick before it went out of
		// reach and the whole track vanished. A destination that is not the tile already
		// stood on means the player is on their way somewhere, whether or not this
		// particular tick happened to move them.
		WorldPoint standing = local == null ? null : local.getWorldLocation();
		LocalPoint destination = client.getLocalDestinationLocation();
		boolean enRoute = destination != null && at != null
			&& (destination.getSceneX() != at.getSceneX()
				|| destination.getSceneY() != at.getSceneY());
		moving = enRoute
			|| (standing != null && lastPlayerPosition != null
				&& !standing.equals(lastPlayerPosition));
		lastPlayerPosition = standing;

		resolveActiveTree();
	}

	/**
	 * Works out which tree is being worked, from the request and where the player stands.
	 *
	 * <p>Separate from the tick because it has to run the moment its inputs change. The
	 * request changes on a click, and a click arrives whenever the player makes one,
	 * while this used to be worked out once a tick - so for the rest of that tick the
	 * answer was the previous tree, and the overlay, which draws some fifty times in
	 * that time, drew the chop box on it. That is the box appearing on the tree just
	 * left for a moment before correcting itself, and no per-tick log could show it:
	 * every reading was taken after this had run, when the two agreed again.</p>
	 *
	 * <p>Deliberately free of the movement bookkeeping above, which must happen once a
	 * tick and exactly once: running that on a click would compare the player against a
	 * position recorded moments earlier and conclude they had stopped.</p>
	 */
	private void resolveActiveTree()
	{
		Player local = client.getLocalPlayer();
		LocalPoint at = local == null ? null : local.getLocalLocation();

		// Cleared up front so that every way out of this leaves the tree and its clickbox
		// agreeing. This runs on clicks as well as on ticks, so a stale one left behind by
		// an early return would be drawn against for the rest of the tick.
		axeClickbox = null;

		if (at == null)
		{
			activeTree = null;
			activeTreeIndex = -1;
			return;
		}

		// The tree being worked is the tree that was clicked. Nothing else can say it, and
		// nothing else is allowed to try: trees grow close enough together that a player
		// stood between two of them is in reach of both, so every attempt to work it out
		// from position has had to choose between them and has sometimes chosen wrong.
		//
		// Position still decides WHETHER the track is up, here and in isChopping(): it
		// answers "has the player got there yet", which is a question about this one tree
		// rather than a search across all of them. Only a click can answer which tree,
		// and a click answers it exactly.
		//
		// So there is no fallback. If no tree has been clicked there is no tree being
		// worked, and the honest thing to draw is nothing. A guess here is wrong often
		// enough to be noticed and quiet enough to be hard to find.
		GameObject best = requestedTreeId > 0 ? treesById.get(requestedTreeId) : null;

		if (best == null)
		{
			activeTree = null;
			activeTreeIndex = -1;
			return;
		}

		int index = indexOf(best.getId());
		if (index < 0)
		{
			activeTree = null;
			activeTreeIndex = -1;
			return;
		}
		if (index != activeTreeIndex)
		{
			// A different tree does not break the combo: the rhythm is yours and carries
			// from one tree to the next. The chop that re-establishes it is forgiven,
			// since walking there is not part of the rhythm.
			resuming = true;
		}

		activeTreeIndex = index;
		activeTree = best;

		// The clickbox belonging to that tree, settled here so everything downstream sees
		// one consistent pair. It is the pull-back target for this tree and no other.
		axeClickbox = clickboxByTree.get(BLOODWOOD_TREES[index]);
	}

	/**
	 * How many tiles the player is from the nearest tile a tree stands on, or -1 if the
	 * tree's footprint cannot be read.
	 *
	 * <p>Zero while stood against it, which is where chopping happens.</p>
	 */
	private int footprintDistance(GameObject tree, LocalPoint from)
	{
		Point min = tree.getSceneMinLocation();
		Point max = tree.getSceneMaxLocation();
		if (min == null || max == null)
		{
			return -1;
		}

		int x = from.getSceneX();
		int y = from.getSceneY();
		int dx = Math.max(0, Math.max(min.getX() - x, x - max.getX()));
		int dy = Math.max(0, Math.max(min.getY() - y, y - max.getY()));
		return Math.max(dx, dy);
	}


	/**
	 * The axe swing itself, which is a tick after the click that asked for it.
	 *
	 * <p>The effects hang off this rather than off the click or off the varbit, because
	 * this is the moment the axe visibly goes into the tree - feedback on the click lands
	 * before anything has happened, and feedback that does not line up with the swing
	 * reads as lag.</p>
	 *
	 * <p>Every axe has its own animation and the two-handed ones have another, so the
	 * whole set is listed. They are the same constants the woodcutting plugin uses, which
	 * cannot be shared from here.</p>
	 */
	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}
		if (!WOODCUTTING_ANIMS.contains(event.getActor().getAnimation()))
		{
			return;
		}

		int tick = client.getTickCount();
		if (tick == chopAnimTick || !isChopping())
		{
			// The animation is re-set as often as the client likes; one swing a tick is
			// the most that can be real.
			return;
		}

		chopAnimTick = tick;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{

		// The wall clock is what a mouse press is timestamped against, so the tick needs
		// one too. Its measured length also absorbs server jitter, which a flat 600ms
		// would push into the grade instead.
		long now = System.currentTimeMillis();
		if (lastTickMillis > 0)
		{
			measuredTickMillis = now - lastTickMillis;
		}
		lastTickMillis = now;
		selfClicksLastTick = selfClicksThisTick;
		treeClicksLastTick = treeClicksThisTick;
		selfClicksThisTick = 0;
		treeClicksThisTick = 0;
		selfHitsThisTick = 0;
		treeHitsThisTick = 0;
		pullBackFractionLastTick = pullBackFraction;
		pullBackFraction = -1;

		updateAxeClickbox();
		updateActiveTree();

		// A beat only means anything while chops keep landing on it. Once the player has
		// stalled for longer than the track survives - notes missed, walked off, stopped
		// to read something - the cycle it describes is not the one they will come back
		// on, so it is dropped and the track asks for a fresh pair instead of alternating
		// around a cadence nobody is keeping.
		int lastOnBeat = Math.max(lastChopTick, beatAnchorTick);
		if (hasBeat() && lastOnBeat >= 0 && client.getTickCount() - lastOnBeat > IDLE_TICKS)
		{
			beatAnchorTick = -1;
		}

		// And the moment the track goes down, whatever took it down - walking off, the
		// tree running out, the axe going away. Whatever cadence was being kept belonged
		// to that stretch of chopping and does not survive it, so coming back starts from
		// the beginning rather than resuming a cycle the player has stepped out of.
		boolean choppingNow = isChopping();
		if (wasChopping && !choppingNow)
		{
			beatAnchorTick = -1;
		}
		wasChopping = choppingNow;

		// Told to the ping thread here, on the tick, because that thread cannot look at
		// the scene itself.
		atBloodwoods = !treesById.isEmpty();

		int tick = client.getTickCount();
		for (int i = 0; i < CHOPPING_PROGRESS.length; i++)
		{
			updateSap(i);

			int value = client.getVarbitValue(CHOPPING_PROGRESS[i]);
			int previous = lastProgress[i];
			lastProgress[i] = value;

			// The first reading of a varbit is not a chop, it is simply the first time it
			// has been looked at - on login, on a hop, or on walking into the area.
			if (previous < 0)
			{
				continue;
			}

			// Only the tree being worked speaks for this player. Every other chopper in
			// the area is advancing a varbit too, and counting those would hand out a
			// combo for somebody else's rhythm.
			if (i != activeTreeIndex)
			{
				continue;
			}

			// The varbit's raw value, which is not yet understood: it climbs far past any
			// plausible chop count, so it is not a tally of chops and must not be read as
			// one. Logged so its real shape can be seen - the step each chop adds, and
			// what it stands at when the tree finally opens.
			// And only while it is actually being chopped. Tapping and collecting move
			// this varbit about as the tree resets, and a chop counted there would start
			// the beat and raise the track over a cycle that is not happening. The stage
			// cannot carry this on its own: collecting empties the bucket before the
			// varbits are read, so a collect arrives looking exactly like a chop. What
			// was asked for at the click is the only thing that still distinguishes them.
			if (!chopIntent || getStage(BLOODWOOD_TREES[i]) != BloodwoodStage.CHOP)
			{
				continue;
			}

			// Only a rise is a chop. A fall is the tree resetting to be started again,
			// which needs nothing done about it: the progress shown comes from the varbit
			// itself, and the combo is a property of your rhythm rather than of the tree,
			// so it survives.
			if (value > previous)
			{
				chopsDone[i]++;

				// What one chop is worth, which is the axe speaking. The chop that opens
				// the tree is clamped so it lands exactly on the target, so it is left
				// out: counting it would drag the average below what the axe really
				// does and overstate how many chops are left.
				if (value < CHOP_PROGRESS_TARGET)
				{
					stepSum += value - previous;
					stepCount++;
				}

				onChop(tick);
			}
			else if (value < previous)
			{
				// The tree has opened and gone back to nothing, so the count starts again.
				chopsDone[i] = 0;
			}
		}

		// After the readings, since what confirms a tap is the tree having moved on from
		// what it looked like when the tap was asked for.
		confirmPendingTap();
	}

	/**
	 * Called on the tick a chop actually landed.
	 *
	 * <p>On the beat means arriving exactly one cycle after the last one. Arriving late is
	 * the only failure worth counting: it is what a missed pull-back or a missed chop
	 * click both produce, and it is what the player can feel.</p>
	 */
	/**
	 * Called on the tick a chop actually landed.
	 *
	 * <p>A chop landing is proof the cycle worked - the game does not advance the tree for
	 * a cycle that failed - so this is never a miss, whatever its timing. What it can be
	 * is off tempo, which is neither a success to reward nor a failure to announce: the
	 * tree came down, the rhythm did not hold. Those pass silently, scoring nothing and
	 * saying nothing, and the combo ends because the run of on-tempo chops genuinely
	 * did.</p>
	 */
	private void onChop(int tick)
	{
		totalChops++;

		// The swing animation is what the effects are timed to, but it is read from an
		// animation set that the game could rename out from under this at any update. If
		// no swing was seen for this chop, the chop itself stands in - a tick later than
		// ideal beats nothing at all, and a silent effect is indistinguishable from a
		// broken one.
		if (chopAnimTick < tick - 1)
		{
			chopAnimTick = tick;
		}

		int since = lastChopTick < 0 ? Integer.MAX_VALUE : tick - lastChopTick;

		// Resuming rather than continuing: the first chop of a trip, the first on a tree
		// just switched to, or the first after a gap long enough that the track had
		// already gone. None of those is a mistake - there is a walk, a camera turn and a
		// right-click in there - so the chop that re-establishes the beat only does that,
		// and the combo waits for it rather than being broken by it.
		if (resuming || since > IDLE_TICKS)
		{
			resuming = false;
			lastJudgement = null;
			lastJudgementTick = -1;
			callout = null;
			pendingClickOffset = 0;
			lastChopTick = tick;
			beatAnchorTick = tick;
			return;
		}

		if (since == CYCLE_TICKS)
		{
			combo++;
			lastJudgement = BloodwoodJudgement.fromOffset(pendingClickOffset);
			score += (long) lastJudgement.getPoints()
				* Math.max(1, Math.min(combo, MAX_MULTIPLIER));
			lastJudgementTick = tick;
			markRecords();

			// A pull-back produced this chop, so the cycle is intact.
			callout = chooseCallout();
		}
		else
		{
			// Mistimed while chopping, which is a dropped rhythm rather than a dropped
			// cycle. Quietly: no grade, no points, no call-out, because nothing went
			// wrong - it just was not the thing being scored.
			combo = 0;
			lastJudgement = null;
			lastJudgementTick = -1;
			callout = null;

			// The run is over, so this is the moment any record it set is worth keeping.
			flushRecords();
		}

		pendingClickOffset = 0;
		lastChopTick = tick;
		beatAnchorTick = tick;
	}


	/**
	 * The game's own word that the pull-back was short: clicked too early, or only once.
	 *
	 * <p>Worth listening for because it is the one failure the player cannot see coming
	 * from the varbits - the tree does not advance, so nothing else distinguishes it from
	 * simply having stopped.</p>
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage()).toLowerCase();
		if (message.contains(PULL_BACK_FAILED) || message.contains(HELD_TOO_LONG))
		{
			// The chop is taken on trust when it is clicked, late or not, because the
			// game accepts a late one and waiting to be sure would cost the snap that
			// makes the feedback worth having. This is the game saying that particular
			// one was not accepted - the axe was never drawn back, or was left too long -
			// so the optimistic hit is taken back off and the miss put up in its place.
			// Correcting on the game's own word beats guessing a deadline for it.
			chopHitMillis = -1;
			chopMissMillis = System.currentTimeMillis();
			registerMiss(client.getTickCount());

			// And the cycle is over, not merely marked wrong. A failed chop leaves the
			// axe undrawn, so the next thing the tree wants is a fresh pair - exactly
			// what it wanted before the first chop of all. Dropping the beat puts the
			// track back into asking for one, with blue notes falling every tick, rather
			// than carrying on alternating around a cycle that is no longer running and
			// showing a red note for a chop that cannot be taken.
			beatAnchorTick = -1;
		}

		// The game saying a tree has almost bled out is worth hearing, but it is not the
		// question the label answers. The label says "this tap finishes it", which is a
		// statement about a tap the player asked for - so it is raised by the click and
		// taken back by the game refusing that click, and by nothing else.
		//
		// Marking the tree here is what made the word disappear and the figure turn green
		// on its own as the sap ran down, with no tap clicked at all: the message arrives
		// because the tree is nearly empty, not because anyone did anything about it.
		if (activeTreeIndex >= 0 && message.contains(TREE_ALMOST_EMPTY))
		{
		}

		// The game refusing a tap, whether or not that tap was one the label cared about.
		// Logged for every refusal so the rule behind it can be measured: the bar reading
		// at the click that was turned down is one side of the threshold, and the reading
		// at the click that finally works is the other.
		if (isTapRefusal(message))
		{
			// And the mark put up on the strength of that click has to come back down,
			// since the click did nothing at all.
			if (pendingTapTree >= 0)
			{
				finalTapClicked.remove(pendingTapTree);
				pendingTapTree = -1;
			}
		}
	}

	/**
	 * Whether a game message is the tree refusing a tap.
	 *
	 * <p>One sentence, taken from the game rather than guessed at. The speculative
	 * alternatives this replaces - "too soon", "already", "again yet" - matched nothing,
	 * which is the trouble with guessing at wording: a check that never fires looks
	 * exactly like a case that never happens.</p>
	 */
	private boolean isTapRefusal(String message)
	{
		return message.contains(WOUND_NOT_NEEDED);
	}

	private void registerMiss(int tick)
	{
		combo = 0;
		lastJudgement = BloodwoodJudgement.BAD;
		lastJudgementTick = tick;
		pendingClickOffset = 0;
	}

	/**
	 * The usable part of a tick: all of it, less the round trip to the server.
	 *
	 * <p>Derived rather than learned. The earlier version tried to find this figure by
	 * watching which clicks worked, which cannot be done honestly from normal play - the
	 * target line sits inside the window, so a player aiming at it never tests the edge,
	 * and every estimator built on that drifted until something failed. It settled at a
	 * third of a tick on a 33ms connection.</p>
	 *
	 * <p>The real figure follows from where the tick boundaries are. A tick observed by
	 * the client at time T left the server half a round trip earlier, and a click made at
	 * time t reaches the server half a round trip later - so measured from the boundary
	 * the client saw, a click is processed in that same server tick only if it is made
	 * less than a whole round trip before the next one. The usable part of a tick is
	 * therefore <code>1 - ping / tickLength</code>: the full height of the axe clickbox,
	 * shortened by exactly the distance to the server and nothing else.</p>
	 *
	 * <p>Against the measured length of the last tick rather than a flat 600ms, so the
	 * window answers to the same clock the notes fall on - and against the same clamped
	 * reading they use, since an unfiltered one lets a single abnormal gap desynchronise
	 * the two. A stall makes it several seconds and the box is drawn taller than the part
	 * of the tick that registers; two ticks in the same millisecond make it zero, and the
	 * division then collapses the window to its floor.</p>
	 *
	 * <p>The spacing of a pull-back's two clicks comes off the end of it. A pull-back is
	 * not one click but a pair, and the first has to be early enough that the second
	 * still lands - so a window drawn at the full usable figure was showing the player
	 * room that only the last click of the pair could use. A note could be clicked well
	 * inside the box and still fail, which is exactly what it felt like. Taking the
	 * spacing off makes the box mean one thing again: a note inside it can be clicked,
	 * and whatever has to follow that click will still fit.</p>
	 *
	 * <p>And the client's own delay in sending the click comes off as well. Writing the
	 * whole thing out: a tick begins on the server at S and the client sees it one way
	 * later, at S plus n. A click made t after that reaches the server at S plus t plus
	 * L plus 2n - the client's delay, then the wire - so it is handled in that same tick
	 * only while t is under T minus the round trip minus L. The round trip was being
	 * taken off and L was not, which is why the bottom of the box still failed.</p>
	 */
	public double effectiveWindow()
	{
		int ping = pingMillis;
		double usable = ping <= 0 ? 1 : 1 - ping / (double) tickLengthMillis();
		return Math.max(MIN_WINDOW,
			Math.min(1, usable - BloodwoodBeat.CLICK_SPACING - INPUT_LATENCY));
	}

	/** The measured round trip, for display. Negative until the first measurement. */
	public int getPingMillis()
	{
		return pingMillis;
	}

	/**
	 * Measures the round trip to the world the player is on.
	 *
	 * <p>Only while there are bloodwoods in the scene. The window this feeds is of no use
	 * anywhere else, and the client has no shared ping service - the world hopper's
	 * readings are package private, so every plugin wanting one sends its own ICMP echo
	 * to the same host. Pinging from a bank or while questing would be adding to that
	 * traffic for a figure nothing is going to read.</p>
	 *
	 * <p>The last reading is kept when leaving, so walking away and coming back does not
	 * start again from nothing.</p>
	 */
	private void measurePing()
	{
		if (!atBloodwoods || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		WorldResult worlds = worldService.getWorlds();
		if (worlds == null)
		{
			return;
		}

		World world = worlds.findWorld(client.getWorld());
		if (world == null)
		{
			return;
		}

		// TCP is allowed as a fallback because ICMP is blocked often enough that
		// refusing it would leave the window permanently underived.
		int ping = Ping.ping(world, true);
		if (ping > 0)
		{
			pingMillis = ping;
		}
	}

	/**
	 * Whether the boxes are worth drawing.
	 *
	 * <p>Either target being clicked is enough, so the track is up from the first click of
	 * a tree rather than appearing only once a chop has already completed - which would
	 * mean it arrived a cycle after it was first wanted.</p>
	 *
	 * <p>A tree that has been chopped through is bleeding rather than choppable, and what
	 * it wants then is tapping, which has no rhythm to it. The cycle has genuinely ended,
	 * so the boxes go rather than hanging over a tree that cannot be chopped.</p>
	 *
	 * <p>And the tree has to have been asked to chop. A tapped or collected tree ends up
	 * in the same state as one waiting to be chopped, so state alone put the track back
	 * up the moment a bucket was collected - before anything had been chopped at all.</p>
	 */
	/**
	 * Whether the player has actually got to the tree they asked for.
	 *
	 * <p>Separate from {@link #isChopping()} because the two answer different questions.
	 * Chopping says the track belongs on this tree; this says the player is standing
	 * where the chopping happens. The boxes want the first - they are landmarks and
	 * should be visible on the way - while the notes want the second, because a note is a
	 * claim about when to click and there is nothing to click until you arrive.</p>
	 */
	public boolean isAtActiveTree()
	{
		if (activeTree == null || moving)
		{
			return false;
		}

		Player local = client.getLocalPlayer();
		LocalPoint at = local == null ? null : local.getLocalLocation();
		if (at == null)
		{
			return false;
		}

		int distance = footprintDistance(activeTree, at);
		return distance >= 0 && distance <= ARRIVAL_DISTANCE;
	}

	public boolean isChopping()
	{
		// Live on the tree last clicked, from the moment it is clicked. Waiting for the
		// player to arrive meant the track appeared only after the walk, so the first
		// cycle at a new tree was the one with no notes on it - the one most worth
		// having them. There is no longer any risk in showing it early, because the tree
		// is named by the click rather than worked out from where the player stands: the
		// notes appear on the tree being walked to, which is the tree they describe.
		//
		// No test for standing still or being in range, and none for the clickbox either.
		// Every tree carries a clickbox at all times, so its presence says nothing about
		// whether anyone is chopping - it was a usable signal only while the clickbox was
		// mistakenly thought to belong to the player.
		if (activeTree == null || activeTreeIndex < 0 || !isActiveTreeChoppable())
		{
			return false;
		}

		// What does end it: the tree ceasing to want chopping, which the stage above
		// covers, and the player going quiet, which this does. Between them a tapped or
		// collected tree drops the track while a tree being worked keeps it, without
		// needing to know which click did what.
		int tick = client.getTickCount();
		return (lastChopTick >= 0 && tick - lastChopTick <= IDLE_TICKS)
			|| (lastInteractionTick >= 0 && tick - lastInteractionTick <= IDLE_TICKS);
	}

	/**
	 * Whether the tree being worked is actually in its chopping step.
	 *
	 * <p>Checked against the stage rather than against the sap alone. A tree holding a
	 * full bucket has no sap either, so sap by itself said "choppable" for a tree whose
	 * next click collects - and anything that stirred the track up while tapping or
	 * collecting put the chopping interface over a cycle that was not chopping.</p>
	 */
	private boolean isActiveTreeChoppable()
	{
		return activeTreeIndex < 0
			|| getStage(BLOODWOOD_TREES[activeTreeIndex]) == BloodwoodStage.CHOP;
	}

	/**
	 * Reads one tree's sap, and retires a final-tap mark that no longer applies.
	 *
	 * <p>The mark is cleared when the sap is gone, which is the tap finishing, and also
	 * if the figure climbs back above a tap's worth - that is the tree having been
	 * chopped again, which starts a fresh run of taps that the old click says nothing
	 * about.</p>
	 */
	private void updateSap(int index)
	{
		sap[index] = client.getVarbitValue(BLEEDING_PROGRESS[index]);

		if (sap[index] <= 0 || sap[index] > TAP_YIELD)
		{
			finalTapClicked.remove(BLOODWOOD_TREES[index]);
		}
	}

	/**
	 * What a tree is waiting for, which is what its label says.
	 *
	 * <p>A bloodwood runs a cycle rather than a single job: chopped through, then tapped
	 * while the sap drains, then collected, then chopped again. Only one of those steps is
	 * the rhythm this plugin is for, so the other two say so instead of being left to
	 * look like nothing is happening.</p>
	 */
	public BloodwoodStage getStage(int objectId)
	{
		int index = indexOf(objectId);
		if (index < 0)
		{
			return BloodwoodStage.CHOP;
		}

		// Read now rather than from the figure cached for display. That figure is taken
		// once a tick and the overlay draws fifty times in one, so for up to a whole tick
		// after a tree changes step the stage still describes the step before it: the
		// track stayed hidden into a chop that had already started, and showed itself
		// during a tap that had already begun. Neither is a tick the player cannot see.
		if (client.getVarbitValue(BLEEDING_PROGRESS[index]) > 0)
		{
			return BloodwoodStage.TAPPING;
		}
		return client.getVarbitValue(BUCKET_PLACED[index]) == BUCKET_FULL
			? BloodwoodStage.COLLECT : BloodwoodStage.CHOP;
	}

	/** Whether this tree's next click collects a full bucket rather than chopping. */
	private boolean isCollectReady(int objectId)
	{
		return getStage(objectId) == BloodwoodStage.COLLECT;
	}

	/**
	 * Whether the tap running on this tree was clicked as its last one.
	 *
	 * <p>Recorded from the click and nothing else. A tap draws off at most a bucket's
	 * worth, so a tap clicked on a tree holding no more than that is the tap that
	 * finishes it - but only the click says a tap was asked for. Reading it out of the
	 * tree's state instead said the same thing about a tree sitting at twenty sap that
	 * nobody had touched, which sends the player away from a tree that still needs
	 * work.</p>
	 */
	public boolean isFinalTap(int objectId)
	{
		return finalTapClicked.contains(objectId);
	}

	/**
	 * Remembers a tap clicked on a tree that only has one tap left in it.
	 *
	 * <p>Recorded as an intention rather than acted on, because a click is not yet a tap.
	 * The player may click a tree and then click somewhere else before ever reaching it,
	 * and restyling the figure at the click told them the tree was finished when nothing
	 * had been done to it at all.</p>
	 *
	 * <p>Checked at the click because this is when both halves are known: what was asked
	 * for, which only the menu option carries, and how much was left when it was asked,
	 * which the tap is about to change.</p>
	 */
	private void noteTapClick(MenuOptionClicked event)
	{
		int id = event.getId();
		int index = indexOf(id);

		// A tap asked of another tree abandons whatever was asked of the last one.
		if (index < 0 || id != pendingTapTree)
		{
			pendingTapTree = -1;
		}

		if (!isTapOption(event) || index < 0)
		{
			return;
		}

		int left = sap[index];
		int barRatio = -1;
		int barScale = -1;
		for (NPC bar : headbars)
		{
			if (getTreeIdForHeadbar(bar) == id)
			{
				// Raw rather than the fraction, because the threshold is likely to be a
				// whole number of bar units and a float would round the answer away.
				barRatio = bar.getHealthRatio();
				barScale = bar.getHealthScale();
				break;
			}
		}

		pendingTapBarRatio = barRatio;
		pendingTapBarScale = barScale;
		lastTapClickTree = id;

		// Whether this click can land at all. A wound still open refuses another tap, so
		// marking the tree would only have to be taken back a moment later when the game
		// says so - and a word that disappears and comes back is worse than one that
		// never moved. Worked out before acting rather than corrected afterwards.
		boolean woundOpen = barScale > 0
			&& barRatio > barScale * RETAP_BAR_THRESHOLD;

		if (left > 0 && left <= TAP_YIELD && !woundOpen)
		{
			// Taken as done the moment it is clicked. A tap of this size finishes the
			// tree, so the word TAP is already stale advice - it is asking for something
			// the player has just done.
			//
			// Waiting for the tree to prove it was what went wrong before: the proof is a
			// bucket moving or the sap figure falling, and neither arrives promptly, so
			// the intention timed out and the word stayed up through the whole drain.
			// Acting on the click and undoing it if the game complains is both quicker
			// and more honest, and it is the same bargain the chop side makes.
			finalTapClicked.add(id);
			pendingTapTree = id;
			pendingTapIndex = index;
			pendingTapSap = left;
			pendingTapBucket = client.getVarbitValue(BUCKET_PLACED[index]);
			pendingTapTick = client.getTickCount();
		}
	}

	/**
	 * Promotes a clicked tap to a real one once the tree shows it has happened.
	 *
	 * <p>The click only said what was wanted. What says it was actually done is the tree
	 * changing: a bucket going on or filling, or the sap starting to come out of it.
	 * Either is proof the tap took effect, and neither can be produced by a click that
	 * was abandoned on the way.</p>
	 *
	 * <p>Given a limited time to show itself. A player who clicks a tree and walks off
	 * leaves an intention that will never be met, and it has to expire rather than wait
	 * for a tap that is not coming - otherwise the next unrelated change to that tree
	 * would be mistaken for this one.</p>
	 */
	private void confirmPendingTap()
	{
		if (pendingTapTree < 0)
		{
			return;
		}

		if (client.getTickCount() - pendingTapTick > PENDING_TAP_TICKS)
		{
			pendingTapTree = -1;
			return;
		}

		boolean bucketMoved = client.getVarbitValue(BUCKET_PLACED[pendingTapIndex])
			!= pendingTapBucket;
		boolean sapFalling = sap[pendingTapIndex] < pendingTapSap;
		if (bucketMoved || sapFalling)
		{
			finalTapClicked.add(pendingTapTree);
			pendingTapTree = -1;
		}
	}

	/** Sap left in a tree by object id, or 0 when it is not bleeding. */
	public int getSap(int objectId)
	{
		int index = indexOf(objectId);
		return index < 0 ? 0 : sap[index];
	}

	/**
	 * How full a sap bar is, from 1 to 0, taken from the bar itself.
	 *
	 * <p>The bar is an NPC, so it carries a health ratio - which is the same number the
	 * game is drawing. Colouring from this rather than from the sap figure means the
	 * colour tracks the thing the player is watching empty: the bar runs down and refills
	 * repeatedly while the total left to tap only falls, so the two say different things
	 * and it is the bar that says when to act.</p>
	 */
	public double getBarFraction(NPC headbar)
	{
		int ratio = headbar.getHealthRatio();
		int scale = headbar.getHealthScale();
		if (ratio < 0 || scale <= 0)
		{
			return 1;
		}
		return Math.max(0, Math.min(1, ratio / (double) scale));
	}

	/** The trees currently in the scene, by object id. */
	public Map<Integer, GameObject> getTrees()
	{
		return treesById;
	}

	private static int indexOf(int objectId)
	{
		for (int i = 0; i < BLOODWOOD_TREES.length; i++)
		{
			if (BLOODWOOD_TREES[i] == objectId)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * How far the current game tick has run, from 0 at its start to 1 at its end.
	 *
	 * <p>On the wall clock, which is the same clock a mouse press is timestamped against.
	 * That matters more than it sounds: this places the falling line, and the press time
	 * decides the grade, so any difference between the two clocks is a grade that
	 * disagrees with what was on screen. Measuring both the same way is what keeps the
	 * line honest.</p>
	 */
	public double getTickProgress()
	{
		if (lastTickMillis <= 0)
		{
			return 0;
		}
		double elapsed = (System.currentTimeMillis() - lastTickMillis)
			/ (double) tickLengthMillis();
		return Math.max(0, Math.min(1, elapsed));
	}

	/**
	 * The beat a given tick asks for, or null if the beat is not yet known.
	 *
	 * <p>Read backwards from the last chop rather than counted forwards from the first:
	 * the cycle is anchored to what the game has actually done, so a broken rhythm
	 * re-anchors on the next chop instead of drifting for the rest of the tree.</p>
	 */
	public BloodwoodBeat beatAt(int tick)
	{
		if (beatAnchorTick < 0)
		{
			return null;
		}
		int delta = tick - beatAnchorTick;
		if (delta <= 0)
		{
			return null;
		}
		return delta % CYCLE_TICKS == 0 ? BloodwoodBeat.CHOP : BloodwoodBeat.PULL_BACK;
	}

	/** Whether a beat has been established from a landed chop yet. */
	public boolean hasBeat()
	{
		return beatAnchorTick >= 0;
	}

	/** The tick the next chop is due on. */
	public int getNextChopTick()
	{
		return beatAnchorTick < 0 ? -1 : beatAnchorTick + CYCLE_TICKS;
	}
}
