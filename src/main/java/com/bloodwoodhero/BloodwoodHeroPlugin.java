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
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
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
	tags = {"bloodwood", "woodcutting", "rhythm", "tick", "darkmeyer", "hero"},
	enabledByDefault = false
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

	/**
	 * How much sap one tap draws off a tree.
	 *
	 * <p>Used only to decide whether a tap being clicked will finish the tree, which is
	 * the question the sap figure exists to answer.</p>
	 */
	private static final int TAP_YIELD = 25;

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

	/** How often the round trip to the game server is re-measured. */
	private static final int PING_PERIOD_SECONDS = 5;

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

	/** The tree each of those bars belongs to, by the same index. */
	private final List<Integer> headbarTrees = new ArrayList<>();

	/** The tree the last chop landed on, or null before the first chop of a run. */
	@Getter
	private GameObject activeTree;

	/** Index of that tree in the varbit and object id arrays, or -1 when none is known. */
	private int activeTreeIndex = -1;

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

	// Wall-clock start of the current tick, and how long the last one ran for.
	private long lastTickMillis;
	private long measuredTickMillis = 600;

	/** Chops into the current tree. */
	@Getter
	private int treeChops;

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
			else if (npc.getId() == NpcID.PLAYER_AXE_CLICKBOX_VIS
				&& axeClickbox == null && here != null
				&& here.equals(npc.getWorldLocation()))
			{
				axeClickbox = npc;
			}
		}
	}

	/** The sap bars currently in the scene, one per bleeding tree. */
	public List<NPC> getHeadbars()
	{
		return headbars;
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
		return bestId;
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
		finalTapClicked.clear();
		pendingTapTree = -1;
		pullBackHitMillis = -1;
		chopHitMillis = -1;
		treeChops = 0;
		combo = 0;
		selfClicksThisTick = 0;
		treeClicksThisTick = 0;
		selfClicksLastTick = 0;
		treeClicksLastTick = 0;
		treesById.clear();
		axeClickbox = null;
		activeTree = null;
		activeTreeIndex = -1;
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

		int left = config.chopsPerTree() - treeChops;
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
			selfClicksThisTick++;
			double at = clickFraction();
			// The FIRST click of the tick, because that is the one the window constrains:
			// the pull-back needs two inside one tick, so what matters is whether the first
			// left room for the second. Recording the second measured the wrong one.
			if (pullBackFraction < 0)
			{
				pullBackFraction = at;
			}
			// Only a click inside the window is a hit worth flashing. One made past the
			// edge is the click the game is about to ignore, and congratulating it would
			// be the overlay disagreeing with the game.
			//
			// Measured against this note rather than the first of the tick. The window is
			// shortened by exactly the spacing so the second click has somewhere to land,
			// so testing the second against the unshifted window rejects it over the very
			// room that shortening reserved - while the overlay, which does offset by the
			// note index, draws it inside the box and consumes it.
			if (at - (selfClicksThisTick - 1) * BloodwoodBeat.CLICK_SPACING <= effectiveWindow())
			{
				pullBackHitMillis = System.currentTimeMillis();
				pullBackHitAt = burstFraction(at);
				// Which of the tick's notes this click took. The second note is spaced
				// above the first, so a burst that did not know which one it belonged to
				// drew the second click on the first note's line - the one already struck
				// and gone.
				pullBackHitClick = selfClicksThisTick - 1;
			}
			lastInteractionTick = client.getTickCount();
			anchorBeat(client.getTickCount());
		}
		else if (isBloodwoodClick(event))
		{
			// Every click on a bloodwood points the plugin at that tree, whatever it was
			// for. Tap and collect are not chops, but they do say which tree is being
			// worked - and knowing that is what lets the stage gate drop the track
			// immediately rather than leaving it up until the idle timer runs out.
			setActiveTree(event.getId());

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
			if (!chopIntent)
			{
				return;
			}

			treeClicksThisTick++;
			lastInteractionTick = client.getTickCount();
			// Where in the tick the click fell, as a position within the part of the tick
			// that actually registers rather than within the whole of it: 0 at the middle
			// of that window and half a window at either edge.
			double window = effectiveWindow();
			double chopAt = clickFraction();
			pendingClickOffset = (chopAt - window / 2) / window;

			if (chopAt <= window)
			{
				chopHitMillis = System.currentTimeMillis();
				chopHitAt = burstFraction(chopAt);
				chopHitClick = treeClicksThisTick - 1;
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
	 * <p>Notes are consumed in order, which is all the player can do with them: the two
	 * pull-back lines of a tick are the first and second click of that tick. The count is
	 * enough to say which are gone without having to give each note an identity.</p>
	 */
	public int getClicksThisTick(BloodwoodBeat beat)
	{
		return beat == BloodwoodBeat.CHOP ? treeClicksThisTick : selfClicksThisTick;
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
	 * <p>Comparing against the tracked clickbox settles both at once, since that one was
	 * already resolved by standing on your own position.</p>
	 */
	private boolean isPullBackClick(MenuOptionClicked event)
	{
		if (!isNpcAction(event.getMenuAction()))
		{
			return false;
		}

		NPC npc = event.getMenuEntry().getNpc();
		return npc != null && npc == axeClickbox;
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
	 */
	private boolean isBloodwoodClick(MenuOptionClicked event)
	{
		if (!isObjectAction(event.getMenuAction()))
		{
			return false;
		}

		return BLOODWOOD_TREE_IDS.contains(event.getId())
			|| Text.removeTags(event.getMenuTarget()).toLowerCase().contains("bloodwood");
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
	 * Starts the beat from a pull-back when no chop has landed yet.
	 *
	 * <p>Without this nothing can be drawn until the first chop completes, which is the
	 * one cycle a new player most needs drawn. A pull-back puts the chop on the tick
	 * after, so the anchor is placed where a chop would have to have been for that to be
	 * true. The first real chop overwrites it, so a wrong guess lasts one cycle.</p>
	 */
	private void anchorBeat(int tick)
	{
		if (beatAnchorTick < 0)
		{
			beatAnchorTick = tick - config.cycleTicks() + 1;
		}
	}

	/** Remembers which tree is being worked, and which varbit therefore speaks for it. */
	private void setActiveTree(int objectId)
	{
		for (int i = 0; i < BLOODWOOD_TREES.length; i++)
		{
			if (BLOODWOOD_TREES[i] == objectId)
			{
				if (activeTreeIndex != i)
				{
					// A different tree means a fresh count, but not a fresh combo: the
					// rhythm is yours and carries from one tree to the next. The chop that
					// re-establishes it is forgiven, since getting to the tree is not part
					// of the rhythm.
					treeChops = 0;
					resuming = true;
				}
				activeTreeIndex = i;
				activeTree = treesById.get(objectId);
				return;
			}
		}
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
		pullBackFractionLastTick = pullBackFraction;
		pullBackFraction = -1;

		updateAxeClickbox();

		// Keep the reference fresh: trees respawn as they regrow, so the object captured
		// at click time goes stale while the id it was found by does not.
		if (activeTreeIndex >= 0)
		{
			GameObject tree = treesById.get(BLOODWOOD_TREES[activeTreeIndex]);
			if (tree != null)
			{
				activeTree = tree;
			}
		}

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

			if (value > previous)
			{
				onChop(tick);
			}
			else if (value < previous)
			{
				// The tree reset, so it is being started again. The combo is a property of
				// your rhythm rather than of the tree, so it survives.
				treeChops = 0;
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
		treeChops++;
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

		if (since == config.cycleTicks())
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
		if (Text.removeTags(event.getMessage()).toLowerCase().contains(PULL_BACK_FAILED))
		{
			registerMiss(client.getTickCount());
		}
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
	 * <p>Run on the background scheduler because it touches the network, and guarded so
	 * it costs nothing while the player is not at a bloodwood.</p>
	 */
	private void measurePing()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
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
	public boolean isChopping()
	{
		if (!chopIntent || !isActiveTreeChoppable())
		{
			return false;
		}
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
		if (sap[index] > 0)
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
		if (left > 0 && left <= TAP_YIELD)
		{
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
		return delta % config.cycleTicks() == 0 ? BloodwoodBeat.CHOP : BloodwoodBeat.PULL_BACK;
	}

	/** The tick the next chop is due on. */
	public int getNextChopTick()
	{
		return beatAnchorTick < 0 ? -1 : beatAnchorTick + config.cycleTicks();
	}
}
