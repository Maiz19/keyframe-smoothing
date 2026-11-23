package com.keyframesmoothing;

import com.google.inject.Provides;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntPredicate;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.GroundObject;
import net.runelite.api.IndexDataBase;
import net.runelite.api.ItemComposition;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.api.kit.KitType;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Smooths animations between their keyframes. Per kind of character and animation (your movement, your attacks, other
 * players' actions, NPCs...) it is off (keyframe to keyframe), plain (the client's even blend), or keyframes: one fluid
 * motion through every keyframe, where each part of the body and the weapon turns along a curve and keeps its shape
 * ({@link PartSpline}). On top: fading ghosts of your weapon behind fast swings.
 *
 * <p>Off and plain go through the client's animation filter, which the game asks for each character and animated object
 * it draws (only with the animation id, also on the GPU plugin's render threads, so the filter only reads a snapshot).
 *
 * <p>Keyframes are worked out from the game's own data as an animation starts, so new weapons and NPCs need nothing
 * ({@link Poses}): the character's model built again as the client builds it and posed by the client at each keyframe
 * and at a few points between keyframes as it blends them, checked once against the keyframe the client draws. A spline
 * is made from those off the client thread and checked against the client's blend (see Rules); steps it would make
 * worse stay the client's. The poses are changed as the renderer is handed each player and NPC
 * ({@link DrawHook}, {@link ActorDrawer}); without a GPU renderer keyframes are smoothed plainly.
 */
@Slf4j
@PluginDescriptor(
	name = "Keyframe Smoothing",
	description = "Smooth animations that keep their keyframes: one fluid motion per action, with weapon ghosts",
	tags = {"animation", "smoothing", "interpolation", "keyframes", "tween", "players", "npcs", "impact"}
)
public class KeyframeSmoothingPlugin extends Plugin
{
	/** Other plugins' messages to this one (see onPluginMessage): the namespace, and asking for a drawn pose. */
	static final String NAMESPACE = "keyframe-smoothing";
	static final String POSE = "pose";
	/** Length of a client cycle in nanoseconds, the unit of animation frame lengths. */
	static final long TICK_NANOS = 20_000_000L;
	/** Animations worked out kept at most, and the memory they may hold (least recently used go first, yours last). */
	private static final int MAX_LEARNT = 48;
	private static final long LEARNT_BYTES = 32L << 20;
	/** Keyframes an animation is tried at before it is left to the client (see work); at most so many tries a frame. */
	private static final int TRIES = 3;
	private static final int TRIES_PER_FRAME = 4;
	/**
	 * The local player's actions per weapon worn, kept across sessions (a hidden setting): "weapon:animation,..;..". So
	 * many weapons and actions of each at most, the least recently played going first.
	 */
	static final String KNOWN_ACTIONS = "knownActions";
	static final int KNOWN_WEAPONS = 40, KNOWN_PER_WEAPON = 12;
	/** Frames an animation to work out ahead waits for its models to load before it is let go. */
	private static final int AHEAD_WAITS = 500;
	/** Config archives of animation, item and spot animation definitions. */
	private static final int SEQUENCE_CONFIG = 12;
	private static final int ITEM_CONFIG = 10;
	private static final int SPOT_ANIMATION_CONFIG = 13;
	/**
	 * An animation's forced priority (from the game cache) from which it is a strike: attacks have 6, blocking and eating
	 * 5, the default.
	 */
	static final int STRIKE_PRIORITY = 6;
	/** Frames in the world without a call through our hook before it counts as left out (see installHook). */
	private static final int HOOK_QUIET_FRAMES = 30;
	/** Frames to wait for another plugin's hook in front of the renderer to step aside (about a minute). */
	private static final int HOOK_WAIT_FRAMES = 3000;
	/** An attack starting within this many client cycles after the special energy dropped is the special (a game tick is 30). */
	static final int SPEC_CYCLES = 45;
	/** The game's enum of each weapon category's attack style enum, and the struct param naming a style (as the attack styles plugin reads them). */
	private static final int WEAPON_STYLES_ENUM = 3908, STYLE_NAME_PARAM = 1407;
	/** A staff category without an entry in WEAPON_STYLES_ENUM: its styles as the attack styles plugin has them. */
	private static final int STAFF_CATEGORY = 22;
	private static final String[] STAFF_STYLES = {"Accurate", "Aggressive", null, "Defensive", "Casting", "Defensive"};
	/** How many of an attack's last hitsplats its hit time is the median of (see HitTimes). */
	private static final int HIT_SAMPLES = 5;
	/** The slots the client joins into a player's model before the weapon's; only items are worn there, never body kits. */
	private static final KitType[] BEFORE_WEAPON = {KitType.HEAD, KitType.CAPE, KitType.AMULET};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyframeSmoothingConfig config;

	@Inject
	private ConfigManager configManager;

	private volatile KeyframeTable table;
	private volatile Settings settings;
	private ExecutorService learner;
	private ActorDrawer drawer;
	private ObjectDrawer objectDrawer;
	private DrawHook hook;
	private int quietFrames;
	private int waitedFrames;
	/** Which draw callbacks are a renderer itself; tests replace it. */
	java.util.function.Predicate<DrawCallbacks> isRenderer = KeyframeSmoothingPlugin::knownRenderer;

	// client thread only
	private final Map<Actor, Track> tracks = new IdentityHashMap<>();
	/** Per animation id: its keyframe lengths and glitch letters, loaded once. */
	private final Map<Integer, AnimationInfo> animations = new HashMap<>();
	private final Map<Long, Learning.Learnt> learnt = new LinkedHashMap<>(16, 0.75f, true);
	/** The learnt animations that are the local player's own: a crowd's come and go without pushing these out. */
	private final Set<Long> yours = new HashSet<>();
	/** Per animation of the local player's attacks: when its own hitsplats came (see hitAt). */
	private final Map<Integer, HitTimes> hitTimes = new HashMap<>();
	private long learntBytes;
	/** Being worked out off the client thread, or failed: not started again. */
	private final Set<Long> learning = new HashSet<>();
	/** Animations on a model that could not be worked out, and why (left to the client); tries so far of the others. */
	final Map<Long, String> unworkable = new HashMap<>();
	private final Map<Long, Integer> tries = new HashMap<>();
	private int triesThisFrame;
	/** Worked out ahead of their first play (see workAhead): not yet checked against the keyframe the client shows. */
	private final Set<Long> unchecked = new HashSet<>();
	/** The local player's actions per weapon worn (item id, -1: none), the latest played last (see KNOWN_ACTIONS). */
	private final Map<Integer, Set<Integer>> knownActions = new LinkedHashMap<>();
	/** The local player's look its animations were last worked out ahead for, and those still to do. */
	private long aheadLook = Long.MIN_VALUE;
	private final ArrayDeque<Integer> ahead = new ArrayDeque<>();
	private int aheadWaits;
	private Poses poses;
	private volatile long lastTickNanos;
	/**
	 * The client cycles of the last few game ticks, latest first, -1: none yet. The game sends an action again on a game
	 * tick, so one sent again unseen within its first keyframe started over on one of these (see Track.sentInFirstKeyframe).
	 */
	private final int[] tickCycles = {-1, -1, -1, -1};
	private Player local;
	/** The animations of the scene's objects whose name is on the smoothing exceptions; replaced as a whole, read by the filter. */
	private volatile Set<Integer> objectExceptions = Set.of();
	/** The scene's objects that were drawn animated when they spawned (a boss like the Great Olm is such objects). */
	private final Set<TileObject> animatedObjects = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Per animated object's model, its keyframe, learning and drawing; read on render threads too. */
	private final Map<DynamicObject, ObjectTrack> objectTracks = new ConcurrentHashMap<>();
	/** Frames drawn (counted before each), and the game cycle of the one before: objects' keyframes moved on in it. */
	private int renders;
	private int lastRenderCycle;
	/** Per spot animation, the animation it plays (-1: none), read from the game cache once. */
	private final Map<Integer, Integer> spotAnimations = new HashMap<>();
	/** Whether the lists name an NPC (by name) or a weapon (by item), for the settings they were read with. */
	private final Map<Lists, Map<String, Boolean>> enemyExcepted = new EnumMap<>(Lists.class);
	private final Map<Lists, Map<Integer, Boolean>> weaponExcepted = new EnumMap<>(Lists.class);
	private Settings listedFor;
	/** Special attacks: the energy last seen and the client cycle it last dropped on (a special was used). */
	private int specEnergy;
	private int specFiredCycle = -SPEC_CYCLES;
	/** A spell was clicked onto a character: the local player's next attack is that cast (see attackStyle). */
	private boolean castClicked;
	/** The weapon found in the local player's model, for ghosts. */
	private WeaponRun weaponRun;
	private int[] weaponRunFaces;
	private int weaponRunItem = -1;
	/** What Animation.getRightHandItem gives for a hand the animation empties. */
	private static final int EMPTIED_HAND = -512;
	private final Map<Long, WeaponFinder.Faces> weapons = new HashMap<>();
	private final Map<Long, Model> weaponModels = new HashMap<>();
	/** Per weapon model with faces it shows plain (see weaponModel): {those faces, its own colours 1, 2 and 3 of them}. */
	private final Map<Long, int[][]> weaponPlain = new HashMap<>();
	private boolean warned;

	/** What the filter reads, on any thread: replaced as a whole before each frame is drawn. */
	private volatile Snapshot snapshot = Snapshot.NONE;
	/** Per animation, the characters wanting it blended this frame less those that don't (see decide). */
	private Map<Integer, Integer> votes = new HashMap<>();
	/** The thread taking a keyframe pose while it does (no blend for that model; other threads ask as usual), else null. */
	private volatile Thread capturing;
	/** The thread building a model again with its own answer for one animation (see remodel), else null. */
	private volatile Thread answering;
	private int answeringId;
	private boolean answer;
	/** The filter that was set when we installed ours (RuneLite's own smoothing, or another plugin's); asked too. */
	private volatile IntPredicate previous;
	/** Set while this thread asks the previous filter, in case that filter wraps ours and asks us back. */
	private final ThreadLocal<boolean[]> askingPrevious = ThreadLocal.withInitial(() -> new boolean[1]);
	private final IntPredicate filter = this::shouldBlend;

	@Provides
	KeyframeSmoothingConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(KeyframeSmoothingConfig.class);
	}

	@Override
	protected void startUp()
	{
		table = KeyframeTable.load();
		settings = new Settings(config);
		learner = Executors.newSingleThreadExecutor();
		poses = new Poses(client);
		knownActions.putAll(knownActions(configManager.getConfiguration(KeyframeSmoothingConfig.GROUP, KNOWN_ACTIONS)));
		drawer = new ActorDrawer(this);
		objectDrawer = new ObjectDrawer(this);
		clientThread.invoke(() ->
		{
			installFilter();
			noteObjects();
		});
	}

	@Override
	protected void shutDown()
	{
		learner.shutdownNow();
		drawer.enabled = false;
		objectDrawer.enabled = false;
		clientThread.invoke(() ->
		{
			if (client.getAnimationInterpolationFilter() == filter)
			{
				client.setAnimationInterpolationFilter(previous);
			}
			previous = null;
			if (hook != null && client.getDrawCallbacks() == hook)
			{
				client.setDrawCallbacks(hook.delegate());
			}
			hook = null;
			snapshot = Snapshot.NONE;
			objectExceptions = Set.of();
			animatedObjects.clear();
			objectTracks.clear();
			spotAnimations.clear();
			tracks.clear();
			enemyExcepted.clear();
			weaponExcepted.clear();
			animations.clear();
			learnt.clear();
			yours.clear();
			hitTimes.clear();
			learntBytes = 0;
			learning.clear();
			unworkable.clear();
			unchecked.clear();
			knownActions.clear();
			ahead.clear();
			aheadLook = Long.MIN_VALUE;
			tries.clear();
			poses.clear();
			weapons.clear();
			weaponModels.clear();
			weaponPlain.clear();
			weaponRun = null;
			weaponRunFaces = null;
			votes = new HashMap<>();
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (KeyframeSmoothingConfig.GROUP.equals(event.getGroup()))
		{
			settings = new Settings(config);
			if (Lists.SMOOTH.key.equals(event.getKey()))
			{
				clientThread.invokeLater(this::noteObjects);
			}
		}
	}

	// ---- objects on the smoothing exceptions: their animations get the opposite of the objects setting ----

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			// the scene's objects spawn again
			objectExceptions = Set.of();
			animatedObjects.clear();
			objectTracks.clear();
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		noteObject(event.getGameObject());
		watch(event.getGameObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		noteObject(event.getDecorativeObject());
		watch(event.getDecorativeObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		noteObject(event.getWallObject());
		watch(event.getWallObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		noteObject(event.getGroundObject());
		watch(event.getGroundObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		animatedObjects.remove(event.getGameObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned event)
	{
		animatedObjects.remove(event.getDecorativeObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		animatedObjects.remove(event.getWallObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned event)
	{
		animatedObjects.remove(event.getGroundObject());
	}

	/** Follows an animated object: which keyframe it shows is asked before each frame (see decideEffects). */
	private void watch(TileObject object)
	{
		if (object != null && animated(object))
		{
			animatedObjects.add(object);
		}
	}

	/** Keeps the animations of an object whose name is on the smoothing exceptions: those of its models that animate. */
	private void noteObject(TileObject object)
	{
		NameList excepted = settings.excepted(Lists.SMOOTH);
		if (excepted == NameList.EMPTY || object == null)
		{
			return;
		}
		ObjectComposition composition = objectComposition(object.getId());
		if (composition == null || composition.getName() == null || !excepted.has(composition.getName()))
		{
			return;
		}
		for (Renderable renderable : renderables(object))
		{
			Animation animation = renderable instanceof DynamicObject ? ((DynamicObject) renderable).getAnimation() : null;
			if (animation != null && !objectExceptions.contains(animation.getId()))
			{
				Set<Integer> grown = new HashSet<>(objectExceptions);
				grown.add(animation.getId());
				objectExceptions = Set.copyOf(grown);
			}
		}
	}

	/** The models an object is drawn with (two for a wall or a decoration; some null). */
	private static Renderable[] renderables(TileObject object)
	{
		if (object instanceof GameObject)
		{
			return new Renderable[]{((GameObject) object).getRenderable()};
		}
		if (object instanceof WallObject)
		{
			return new Renderable[]{((WallObject) object).getRenderable1(), ((WallObject) object).getRenderable2()};
		}
		if (object instanceof DecorativeObject)
		{
			return new Renderable[]{((DecorativeObject) object).getRenderable(), ((DecorativeObject) object).getRenderable2()};
		}
		if (object instanceof GroundObject)
		{
			return new Renderable[]{((GroundObject) object).getRenderable()};
		}
		return new Renderable[0];
	}

	/** Whether an object is drawn with a model that animates. */
	private static boolean animated(TileObject object)
	{
		for (Renderable renderable : renderables(object))
		{
			if (renderable instanceof DynamicObject && ((DynamicObject) renderable).getAnimation() != null)
			{
				return true;
			}
		}
		return false;
	}

	/** A tile's objects (some slots empty). */
	private static TileObject[] objects(Tile tile)
	{
		GameObject[] game = tile.getGameObjects() == null ? new GameObject[0] : tile.getGameObjects();
		TileObject[] all = new TileObject[game.length + 3];
		System.arraycopy(game, 0, all, 0, game.length);
		all[game.length] = tile.getDecorativeObject();
		all[game.length + 1] = tile.getWallObject();
		all[game.length + 2] = tile.getGroundObject();
		return all;
	}

	/** An object's composition: the one it shows now, when that varies. */
	private ObjectComposition objectComposition(int id)
	{
		ObjectComposition composition = client.getObjectDefinition(id);
		return composition != null && composition.getImpostorIds() != null ? composition.getImpostor() : composition;
	}

	/** The scene's objects over again: when the list changes, and as the plugin starts (the animated ones too). */
	private void noteObjects()
	{
		objectExceptions = Set.of();
		WorldView top = client.getTopLevelWorldView();
		// before logging in there is no scene, or one without tiles yet
		Tile[][][] tiles = top == null || top.getScene() == null ? null : top.getScene().getTiles();
		if (tiles == null)
		{
			return;
		}
		for (Tile[][] plane : tiles)
		{
			for (Tile[] row : plane == null ? new Tile[0][] : plane)
			{
				for (Tile tile : row == null ? new Tile[0] : row)
				{
					if (tile != null)
					{
						for (TileObject object : objects(tile))
						{
							noteObject(object);
							watch(object);
						}
					}
				}
			}
		}
	}

	// ---- hooks ----

	/** Puts our filter in front of whatever filter is set now, also after another plugin replaced it. */
	private void installFilter()
	{
		IntPredicate current = client.getAnimationInterpolationFilter();
		if (current != filter)
		{
			previous = current;
			client.setAnimationInterpolationFilter(filter);
		}
	}

	/**
	 * Keeps our hook right in front of the renderer. Renderers set themselves when they start and null when they stop,
	 * so this is checked every frame.
	 *
	 * <p>Other plugins hook in front of the renderer too. Ours goes directly on the renderer, never in front of another
	 * plugin's hook: such a plugin puts itself back in front of whatever is there, and one that finds ours in front of it
	 * leaves its old hook behind ours, the two wrapping each other at every load until the stack overflows (Personal Space).
	 * Personal Space steps aside while an area loads, so that is when ours goes in; another plugin's
	 * hook that never steps aside is gone round after HOOK_WAIT_FRAMES. Once in, ours stays as long as the renderer is
	 * still called through it; only a new renderer (nothing through ours for HOOK_QUIET_FRAMES) gets a new hook.
	 */
	void installHook()
	{
		DrawCallbacks current = client.getDrawCallbacks();
		DrawHook ours = hook;
		if (current == null || current == ours)
		{
			// counted afresh each frame, so the count never runs over in a long session
			if (ours != null)
			{
				ours.calls = 0;
			}
			quietFrames = 0;
			return;
		}
		if (ours != null && ours.calls > 0)
		{
			// still drawn through, behind another plugin's hook
			ours.calls = 0;
			quietFrames = 0;
			return;
		}
		GameState state = client.getGameState();
		if (state != GameState.LOGGED_IN && state != GameState.LOADING)
		{
			return;
		}
		if (ours != null && state == GameState.LOGGED_IN && ++quietFrames < HOOK_QUIET_FRAMES)
		{
			return;
		}
		DrawCallbacks target = current instanceof DrawHook ? ((DrawHook) current).delegate() : current;
		if (!isRenderer.test(target) && ++waitedFrames < HOOK_WAIT_FRAMES)
		{
			return;
		}
		quietFrames = 0;
		waitedFrames = 0;
		hook = new DrawHook(target, drawer, objectDrawer);
		client.setDrawCallbacks(hook);
		log.debug("Drawing players and NPCs through {}", target.getClass().getName());
	}

	/** The GPU plugin and 117 HD, as opposed to another plugin's hook in front of one. */
	static boolean knownRenderer(DrawCallbacks callbacks)
	{
		String name = callbacks.getClass().getName();
		return name.startsWith("net.runelite.client.plugins.gpu.") || name.startsWith("rs117.hd.");
	}

	/** Whether poses can be changed: a renderer draws through our hook. */
	private boolean hooked()
	{
		return hook != null && client.getDrawCallbacks() != null;
	}

	// ---- each client cycle: which keyframe every character shows, and since when ----

	/**
	 * The Gauntlet's chop and the sound the game plays for it at the tree (not the animation's own, which has the player
	 * as its source), each time it sends the chop: a game tick after the chop starts and then every third (see
	 * Track.againCycle, ActorDrawer.twice).
	 */
	static final int GAUNTLET_CHOP = 8325, GAUNTLET_CHOP_SOUND = 2735;
	/** Client cycles in a game tick. */
	static final int GAME_TICK_CYCLES = 30;
	/** The game cycle the game last played the Gauntlet's chop sound itself, -1 before. */
	private int chopSoundCycle = -1;

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		if (event.getSource() == null && event.getSoundId() == GAUNTLET_CHOP_SOUND)
		{
			chopSoundCycle = client.getGameCycle();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		System.arraycopy(tickCycles, 0, tickCycles, 1, tickCycles.length - 1);
		tickCycles[0] = client.getGameCycle();
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		lastTickNanos = System.nanoTime();
		int cycle = client.getGameCycle();
		int energy = client.getVarpValue(VarPlayerID.SA_ENERGY);
		if (energy < specEnergy)
		{
			specFiredCycle = cycle;
		}
		specEnergy = energy;
		WorldView top = client.getTopLevelWorldView();
		if (top == null)
		{
			tracks.clear();
			return;
		}
		follow(top, cycle);
		for (WorldView worldView : top.worldViews())
		{
			follow(worldView, cycle);
		}
		tracks.values().removeIf(track -> track.seenCycle != cycle);
	}

	private void follow(WorldView worldView, int cycle)
	{
		for (Player player : worldView.players())
		{
			follow(player, cycle);
		}
		for (NPC npc : worldView.npcs())
		{
			follow(npc, cycle);
		}
	}

	private void follow(Actor actor, int cycle)
	{
		Track track = tracks.computeIfAbsent(actor, a -> new Track());
		track.seenCycle = cycle;
		// the game blends the action animation when there is one, else the pose (standing, walking)
		int animationId = actor.getAnimation();
		boolean action = animationId != -1;
		int frame = action ? actor.getAnimationFrame() : actor.getPoseAnimationFrame();
		if (!action)
		{
			animationId = actor.getPoseAnimation();
		}
		if (animationId != track.animationId)
		{
			track.animationId = animationId;
			track.action = action;
			track.attack = action && attacks(actor);
			track.skill = action && !track.attack && SkillSwings.of(lengths(animationId)) != null;
			track.restartsWhenSent = action && !track.attack && restartsWhenSent(animationId);
			// the Gauntlet's chop is sent again a game tick after it starts, with the game's own chop sound, unless that
			// sound came as it started (then it is not)
			track.againCycle = animationId == GAUNTLET_CHOP && action && actor == client.getLocalPlayer() && chopSoundCycle != cycle
				? cycle + GAME_TICK_CYCLES : -1;
			startAttack(track, actor, cycle);
			track.repeats = false;
			track.restarting = false;
			track.restartOffset = null;
			track.cutTime = Double.NaN;
			track.frame = -1;
		}
		if (track.shows(frame, cycle, action && frame < track.frame ? lengths(animationId) : null))
		{
			if (track.attack)
			{
				// the same attack again (an attack doesn't loop): a special or not, and its style, on its own
				startAttack(track, actor, cycle);
			}
		}
		else if (track.restartsWhenSent)
		{
			track.sentInFirstKeyframe(cycle, tickCycles, lengths(animationId));
		}
	}

	/** An action starting: whether it is a special attack and, for the local player's attacks, its combat style. */
	private void startAttack(Track track, Actor actor, int cycle)
	{
		track.special = track.attack && special(actor, cycle);
		track.style = track.attack && actor == client.getLocalPlayer() ? attackStyle() : null;
	}

	/** The combat style of the local player's attack starting now: a spell clicked onto a character, else the weapon's style. */
	private CombatStyle attackStyle()
	{
		boolean cast = castClicked;
		castClicked = false;
		return cast ? CombatStyle.MAGIC : CombatStyle.of(weaponStyles(), client.getVarpValue(VarPlayerID.COM_MODE));
	}

	/** The names of the equipped weapon's attack styles, by the index the style setting (COM_MODE) picks; null for none. */
	private String[] weaponStyles()
	{
		int category = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
		EnumComposition categories = client.getEnum(WEAPON_STYLES_ENUM);
		int stylesEnum = categories == null ? -1 : categories.getIntValue(category);
		if (stylesEnum == -1)
		{
			return category == STAFF_CATEGORY ? STAFF_STYLES : null;
		}
		EnumComposition styles = client.getEnum(stylesEnum);
		if (styles == null)
		{
			return null;
		}
		int[] structs = styles.getIntVals();
		String[] names = new String[structs.length];
		for (int i = 0; i < structs.length; i++)
		{
			names[i] = client.getStructComposition(structs[i]).getStringValue(STYLE_NAME_PARAM);
		}
		return names;
	}

	/** A spell cast onto a character marks the next attack as magic; another click on a character or the ground clears it. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		switch (event.getMenuAction())
		{
			case WIDGET_TARGET_ON_NPC:
			case WIDGET_TARGET_ON_PLAYER:
				castClicked = "Cast".equals(event.getMenuOption());
				break;
			case WALK:
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGHTH_OPTION:
				castClicked = false;
				break;
			default:
				break;
		}
	}

	/** The local player's attack starting now is a special attack: its special energy has just dropped. */
	private boolean special(Actor actor, int cycle)
	{
		return actor == client.getLocalPlayer() && cycle - specFiredCycle <= SPEC_CYCLES;
	}

	/** An attack on a player, or on an NPC that can be attacked. */
	private static boolean attacks(Actor actor)
	{
		Actor target = actor.getInteracting();
		return target instanceof Player || target instanceof NPC && attackable((NPC) target);
	}

	/** An NPC that can be attacked: a monster. */
	private static boolean attackable(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			return false;
		}
		if (composition.getCombatLevel() > 0)
		{
			return true;
		}
		String[] actions = composition.getActions();
		return actions != null && Arrays.asList(actions).contains("Attack");
	}

	// ---- the shift-right-click menu: smooth and ghosts on or off, by weapon and by NPC ----

	/**
	 * The local player's own hitsplat on its target: when it came in the attack it shows (ticks from the animation's
	 * start), for the attack's hit to land then (see hitAt, ActorDrawer.synced).
	 */
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Player me = client.getLocalPlayer();
		Actor target = event.getActor();
		if (me == null || target == me || me.getInteracting() != target || !event.getHitsplat().isMine())
		{
			return;
		}
		Track track = tracks.get(me);
		int[] lengths = track == null || !track.action || !track.attack ? null : lengths(track.animationId);
		if (lengths == null || track.frame < 0 || track.frame >= lengths.length)
		{
			return;
		}
		double time = ticksInto(track);
		for (int k = 0; k < track.frame; k++)
		{
			time += lengths[k];
		}
		hitTimes.computeIfAbsent(track.animationId, id -> new HitTimes()).add(time);
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		Settings now = settings;
		if (!(now.smoothMenu || now.ghostMenu) || !client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}
		MenuEntry entry = event.getMenuEntry();
		if (entry.getType() == MenuAction.EXAMINE_NPC)
		{
			NPC npc = entry.getNpc();
			if (npc != null && npc.getName() != null && attackable(npc))
			{
				entries(entry, npc.getName(), now.smoothNpcs, now, Lists.SMOOTH);
			}
		}
		else if (entry.getType() == MenuAction.EXAMINE_OBJECT)
		{
			// an object drawn with a model that animates
			TileObject object = objectOf(entry);
			ObjectComposition composition = object != null && animated(object) ? objectComposition(object.getId()) : null;
			if (composition != null && composition.getName() != null && !"null".equals(composition.getName()))
			{
				entries(entry, composition.getName(), now.objects, now, Lists.SMOOTH);
			}
		}
		else if ("Examine".equals(entry.getOption()) || "Remove".equals(entry.getOption()))
		{
			// an item in the inventory or bank (its Examine), or worn (its Remove)
			int item = entry.getItemId();
			Widget widget = item > 0 ? null : entry.getWidget();
			item = item > 0 ? item : widget != null ? widget.getItemId() : -1;
			ItemComposition composition = item > 0 ? client.getItemDefinition(item) : null;
			if (composition != null && wieldable(composition))
			{
				entries(entry, composition.getName(), now.smoothMe, now, Lists.GHOSTS, Lists.SMOOTH);
			}
		}
	}

	/** The object a menu entry is about: the one with its id on the entry's tile. */
	private TileObject objectOf(MenuEntry entry)
	{
		WorldView worldView = client.getWorldView(entry.getWorldViewId());
		Tile[][][] tiles = worldView == null || worldView.getScene() == null ? null : worldView.getScene().getTiles();
		int x = entry.getParam0(), y = entry.getParam1();
		if (tiles == null || x < 0 || y < 0 || x >= tiles[0].length || y >= tiles[0][0].length)
		{
			return null;
		}
		Tile tile = tiles[worldView.getPlane()][x][y];
		for (TileObject object : tile == null ? new TileObject[0] : objects(tile))
		{
			if (object != null && object.getId() == entry.getIdentifier())
			{
				return object;
			}
		}
		return null;
	}

	private static boolean wieldable(ItemComposition composition)
	{
		String[] actions = composition.getInventoryActions();
		return actions != null && Arrays.asList(actions).contains("Wield");
	}

	/**
	 * Our entries, for the lists whose menu is on: off for the name when it has what the list is about now, else on;
	 * either way by putting the name on the list or taking it off. Each goes right below the entry the game added, so
	 * the last one made shows highest, and none takes the left click.
	 */
	private void entries(MenuEntry below, String name, boolean smoothed, Settings now, Lists... lists)
	{
		for (Lists list : lists)
		{
			if (now.menu(list))
			{
				boolean listed = now.excepted(list).has(name);
				boolean on = now.bySettings(list, smoothed) != listed;
				client.getMenu().createMenuEntry(client.getMenu().getMenuEntries().length - 1)
					.setOption(on ? list.off : list.on)
					.setTarget(below.getTarget())
					.setType(MenuAction.RUNELITE)
					.onClick(e -> toggle(list, name, !listed));
			}
		}
	}

	/** Puts the name on the list, or takes it off (a wildcard that matches it stays). */
	private void toggle(Lists list, String name, boolean listed)
	{
		String text = configManager.getConfiguration(KeyframeSmoothingConfig.GROUP, list.key);
		configManager.setConfiguration(KeyframeSmoothingConfig.GROUP, list.key, NameList.with(text, name, listed));
	}

	/** Whether the local player's weapon leaves ghosts: the setting, the opposite when the weapon is on the exceptions. */
	boolean ghosts()
	{
		Settings now = settings;
		return now.ghosts != (local != null && excepted(Lists.GHOSTS, local, now));
	}

	// ---- before each frame: how each character is drawn, and working out its animation ----

	/**
	 * Ahead of other plugins' BeforeRender: what the client is told to blend decides the models they get this frame, and
	 * one asking for a pose (see onPluginMessage) gets the pose of this frame.
	 */
	@Subscribe(priority = 1)
	public void onBeforeRender(BeforeRender event)
	{
		installFilter();
		installHook();
		votes = new HashMap<>();
		triesThisFrame = 0;
		local = client.getLocalPlayer();
		WorldView top = client.getTopLevelWorldView();
		if (top != null)
		{
			int localAnimation = -1;
			if (local != null)
			{
				localAnimation = decide(local, -1);
			}
			decide(top, localAnimation);
			for (WorldView worldView : top.worldViews())
			{
				decide(worldView, localAnimation);
			}
			workAhead();
		}
		Settings now = settings;
		snapshot = new Snapshot(majority(votes), majority(decideEffects(top)), now.objects, objectExceptions);
	}

	// ---- other plugins asking how a character is drawn ----

	/**
	 * Another plugin asks for the pose a character is drawn in this frame (the tile-marker plugin cuts the player's
	 * silhouette out of its marks before the scene is drawn, from the client's pose otherwise): namespace
	 * {@value #NAMESPACE}, name {@value #POSE}, data {@code actor} (Actor) and {@code model} (its Model as the client posed
	 * it this frame). Answered on the client thread by putting {@code x}, {@code y} and {@code z} (float[] vertex
	 * positions, reused by the next answer) into that map, when the pose drawn is not the model's own.
	 */
	@Subscribe
	public void onPluginMessage(PluginMessage message)
	{
		if (!NAMESPACE.equals(message.getNamespace()) || !POSE.equals(message.getName()) || drawer == null || !hooked()
			|| !client.isClientThread())
		{
			return;
		}
		Map<String, Object> data = message.getData();
		Object actor = data.get("actor"), model = data.get("model");
		if (!(actor instanceof Actor) || !(model instanceof Model))
		{
			return;
		}
		float[][] pose = drawer.posed((Actor) actor, (Model) model);
		if (pose == null)
		{
			return;
		}
		try
		{
			data.put("x", pose[0]);
			data.put("y", pose[1]);
			data.put("z", pose[2]);
		}
		catch (UnsupportedOperationException e)
		{
			// asked in a map that can't hold the answer: the asker keeps the client's pose
		}
	}

	/** Per animation, whether more want it blended than not (as many: not). */
	static Map<Integer, Boolean> majority(Map<Integer, Integer> votes)
	{
		Map<Integer, Boolean> answers = new HashMap<>();
		votes.forEach((animationId, count) -> answers.put(animationId, count > 0));
		return answers;
	}

	/**
	 * Which animations of objects and effects (the Great Olm, a teleport's graphic, a spell's projectile) the client can
	 * blend this frame: per animation, how many showing it are at a keyframe that blends well less how many aren't; one
	 * drawn on the client thread whose answer is the other is drawn again with its own (see own). Animations not seen here (an object that only started to animate after it spawned) are
	 * left out where they have a glitch anywhere (see decision). Follows the animated objects on the way.
	 */
	private Map<Integer, Integer> decideEffects(WorldView top)
	{
		Map<Integer, Integer> effects = new HashMap<>();
		int cycle = client.getGameCycle();
		renders++;
		for (TileObject object : animatedObjects)
		{
			for (Renderable renderable : renderables(object))
			{
				if (renderable instanceof DynamicObject && ((DynamicObject) renderable).getAnimation() != null)
				{
					ObjectTrack track = follow(object, (DynamicObject) renderable, cycle);
					effects.merge(track.animationId, track.blend ? 1 : -1, Integer::sum);
				}
			}
		}
		objectTracks.values().removeIf(track -> track.seen != renders);
		lastRenderCycle = cycle;
		if (top != null)
		{
			effects(effects, top);
			for (WorldView worldView : top.worldViews())
			{
				effects(effects, worldView);
			}
		}
		Iterable<Projectile> projectiles = client.getProjectiles();
		for (Projectile projectile : projectiles == null ? List.<Projectile>of() : projectiles)
		{
			Animation animation = projectile.getAnimation();
			if (animation != null)
			{
				shows(effects, animation.getId(), projectile.getAnimationFrame());
			}
		}
		return effects;
	}

	/** The spot animations on a world view's characters (a teleport's graphic) and its graphics objects (a spell landing). */
	private void effects(Map<Integer, Integer> effects, WorldView worldView)
	{
		for (Player player : worldView.players())
		{
			spotAnimations(effects, player);
		}
		for (NPC npc : worldView.npcs())
		{
			spotAnimations(effects, npc);
		}
		Iterable<GraphicsObject> graphics = worldView.getGraphicsObjects();
		for (GraphicsObject graphic : graphics == null ? List.<GraphicsObject>of() : graphics)
		{
			Animation animation = graphic.getAnimation();
			if (animation != null)
			{
				shows(effects, animation.getId(), graphic.getAnimationFrame());
			}
		}
	}

	private void spotAnimations(Map<Integer, Integer> effects, Actor actor)
	{
		if (actor.getSpotAnims() == null)
		{
			return;
		}
		for (ActorSpotAnim spot : actor.getSpotAnims())
		{
			int animationId = spotAnimation(spot.getId());
			if (animationId >= 0)
			{
				shows(effects, animationId, spot.getFrame());
			}
		}
	}

	/** The animation a spot animation plays, or -1. */
	private int spotAnimation(int id)
	{
		return spotAnimations.computeIfAbsent(id, spot ->
		{
			byte[] data = client.getIndexConfig().loadData(SPOT_ANIMATION_CONFIG, spot);
			return data == null ? -1 : SpotAnimationConfig.animation(data);
		});
	}

	/** Something shows keyframe frame of an animation: blended unless that keyframe is a glitch when blended. */
	private void shows(Map<Integer, Integer> effects, int animationId, int frame)
	{
		effects.merge(animationId, glitch(info(animationId), frame) ? -1 : 1, Integer::sum);
	}

	/** Whether a keyframe is a glitch when the client blends it into the next (a wrong-way turn, a jump). */
	private static boolean glitch(AnimationInfo info, int k)
	{
		return info != null && info.letters != null && k >= 0 && k < info.letters.length
			&& (info.letters[k] == KeyframeTable.TURN || info.letters[k] == KeyframeTable.JUMP);
	}

	/**
	 * An object or effect drawn on the client thread: the model to draw, built again with its own answer where the
	 * client's (shared by all showing its animation, see decideEffects) is the other.
	 */
	Model own(Renderable renderable, Model model)
	{
		int animationId;
		boolean blend;
		if (renderable instanceof DynamicObject)
		{
			ObjectTrack track = objectTracks.get(renderable);
			Animation animation = ((DynamicObject) renderable).getAnimation();
			if (animation == null)
			{
				return model;
			}
			animationId = animation.getId();
			// a keyframe it moved on to as it was drawn now: as the others not followed
			blend = track != null && track.animationId == animationId && track.frame == ((DynamicObject) renderable).getAnimFrame()
				? track.blend : !glitch(info(animationId), ((DynamicObject) renderable).getAnimFrame());
		}
		else if (renderable instanceof ActorSpotAnim)
		{
			animationId = spotAnimation(((ActorSpotAnim) renderable).getId());
			blend = !glitch(info(animationId), ((ActorSpotAnim) renderable).getFrame());
		}
		else if (renderable instanceof GraphicsObject && ((GraphicsObject) renderable).getAnimation() != null)
		{
			animationId = ((GraphicsObject) renderable).getAnimation().getId();
			blend = !glitch(info(animationId), ((GraphicsObject) renderable).getAnimationFrame());
		}
		else if (renderable instanceof Projectile && ((Projectile) renderable).getAnimation() != null)
		{
			animationId = ((Projectile) renderable).getAnimation().getId();
			blend = !glitch(info(animationId), ((Projectile) renderable).getAnimationFrame());
		}
		else
		{
			return model;
		}
		Snapshot now = snapshot;
		if (animationId < 0 || now.decisions.containsKey(animationId) || now.objects == now.objectExceptions.contains(animationId)
			|| blend == decision(animationId))
		{
			// a character's animation too, or objects not blended: the answer is the same for all
			return model;
		}
		Model again = remodel(renderable, animationId, blend);
		return again != null ? again : model;
	}

	// ---- animated objects (the Great Olm): followed, blended at every keyframe but their glitches ----

	/**
	 * Follows an animated object before each frame: the keyframe it shows (it moved on as it was drawn in the frame
	 * before) and whether the client should blend it. Objects are not drawn along a spline: their models can't be built
	 * again from the game's data as a character's can (see Poses).
	 */
	private ObjectTrack follow(TileObject object, DynamicObject dynamic, int cycle)
	{
		ObjectTrack track = objectTracks.computeIfAbsent(dynamic, d -> new ObjectTrack());
		track.seen = renders;
		int animationId = dynamic.getAnimation().getId();
		if (animationId != track.animationId)
		{
			track.animationId = animationId;
			track.lengths = lengths(animationId);
			track.frame = -1;
		}
		if (dynamic.getAnimFrame() != track.frame)
		{
			track.frame = dynamic.getAnimFrame();
			track.startCycle = lastRenderCycle;
		}
		boolean on = settings.objects != objectExceptions.contains(animationId);
		track.blend = on && !glitch(info(animationId), track.frame);
		return track;
	}

	/** The client's game cycle (any thread). */
	int gameCycle()
	{
		return client.getGameCycle();
	}

	/** The part of a client cycle since the last began (any thread). */
	double cyclePart()
	{
		return Math.min(1, Math.max(0, (System.nanoTime() - lastTickNanos) / (double) TICK_NANOS));
	}

	private void decide(WorldView worldView, int localAnimation)
	{
		for (Player player : worldView.players())
		{
			if (player != local)
			{
				decide(player, localAnimation);
			}
		}
		for (NPC npc : worldView.npcs())
		{
			decide(npc, localAnimation);
		}
	}

	/**
	 * Works out how the character is drawn this frame and whether the client blends its keyframe, and learns its
	 * animation; returns the animation the filter will be asked about.
	 *
	 * @param localAnimation the local player's animation, whose answer no one else may change
	 */
	private int decide(Actor actor, int localAnimation)
	{
		Track track = tracks.get(actor);
		if (track == null || track.animationId == -1)
		{
			return -1;
		}
		Settings now = settings;
		Smoothing mode = mode(actor, track, now);
		if (mode == Smoothing.KEYFRAMES && !hooked())
		{
			mode = Smoothing.PLAIN;
		}
		track.mode = mode;
		track.standing = !track.action || actor.getPoseAnimation() == actor.getIdlePoseAnimation();
		boolean blend = blends(track, mode, now);
		track.blend = blend;
		if (track.animationId == localAnimation)
		{
			return track.animationId;
		}
		// characters sharing an animation share the client's answer: the local player's, else what most of them want;
		// the others are drawn again with their own (see ActorDrawer)
		votes.merge(track.animationId, blend ? 1 : -1, Integer::sum);
		if (mode == Smoothing.KEYFRAMES)
		{
			work(actor, track);
		}
		return track.animationId;
	}

	/** The setting for the character's animation; the opposite for an NPC, or the weapon of an attack, on the exceptions. */
	Smoothing mode(Actor actor, Track track, Settings now)
	{
		if (actor instanceof NPC)
		{
			return now.smoothNpcs != excepted(Lists.SMOOTH, (NPC) actor, now) ? Smoothing.KEYFRAMES : Smoothing.OFF;
		}
		boolean on = actor == local ? now.smoothMe : now.smoothPlayers;
		if (track.attack)
		{
			on = on != excepted(Lists.SMOOTH, (Player) actor, now);
		}
		return on ? Smoothing.KEYFRAMES : Smoothing.OFF;
	}

	/** Whether a list names an NPC. */
	private boolean excepted(Lists list, NPC npc, Settings now)
	{
		freshLists(now);
		String name = npc.getName();
		return name != null && enemyExcepted.computeIfAbsent(list, l -> new HashMap<>())
			.computeIfAbsent(name, n -> now.excepted(list).has(n));
	}

	/** Whether a list names a player's wielded weapon, by the item's name. */
	private boolean excepted(Lists list, Player player, Settings now)
	{
		freshLists(now);
		PlayerComposition composition = player.getPlayerComposition();
		int item = composition == null ? -1 : composition.getEquipmentId(KitType.WEAPON);
		return item >= 0 && weaponExcepted.computeIfAbsent(list, l -> new HashMap<>()).computeIfAbsent(item, id ->
		{
			ItemComposition weapon = client.getItemDefinition(id);
			return weapon != null && weapon.getName() != null && now.excepted(list).has(weapon.getName());
		});
	}

	/** The lists' answers were read with these settings; with others, they are read again. */
	private void freshLists(Settings now)
	{
		if (listedFor != now)
		{
			enemyExcepted.clear();
			weaponExcepted.clear();
			listedFor = now;
		}
	}

	/** Whether the client blends the character's current keyframe into the next. */
	private boolean blends(Track track, Smoothing mode, Settings now)
	{
		if (mode == Smoothing.OFF)
		{
			return false;
		}
		int k = track.frame;
		boolean glitch = glitch(info(track.animationId), k);
		if (mode == Smoothing.KEYFRAMES)
		{
			Learning.Learnt known = learnt.get(key(track.animationId, track.shape));
			if (known == null)
			{
				// not worked out (yet): the client's blend without its glitches
				return !glitch;
			}
			if (known.spline.smooths(k))
			{
				return true;
			}
			// worked out from the client's own blend, which shows where it goes wrong: the keyframe table (made ahead of
			// time, from an older game) is not asked
			glitch = known.glitch(k);
		}
		return !glitch;
	}

	/**
	 * Works out the character's animation on its model (see Poses) the first time it shows on it, checked against the
	 * keyframe the client shows: from then on it is drawn along its spline. One that can't be worked out is left to the
	 * client after TRIES keyframes. Also while the character walks (an attack begun on the way to its target was the
	 * client's until one was begun standing: the first blow of a fight). One worked out ahead (see workAhead) is only
	 * checked.
	 */
	private void work(Actor actor, Track track)
	{
		int[] lengths = lengths(track.animationId);
		if (lengths == null || lengths.length < 2 || triesThisFrame >= TRIES_PER_FRAME)
		{
			return;
		}
		// the action as it plays: not the pose animation during its start delay (a spot animation is drawn on its own:
		// not in this model)
		if (track.overdue || track.triedCycle == track.startCycle)
		{
			return;
		}
		if (track.shape != 0 && settled(key(track.animationId, track.shape)))
		{
			return;
		}
		track.triedCycle = track.startCycle;
		triesThisFrame++;
		workOut(actor, track, lengths);
	}

	/** Whether nothing is left to do for an animation on a model: worked out and checked, being worked out, or left to the client. */
	private boolean settled(long key)
	{
		return !unchecked.contains(key) && (learnt.containsKey(key) || learning.contains(key) || unworkable.containsKey(key));
	}

	private void workOut(Actor actor, Track track, int[] lengths)
	{
		// the model as the client draws this keyframe, unblended: the check, and which model it is
		Model model;
		capturing = Thread.currentThread();
		try
		{
			model = actor.getModel();
		}
		finally
		{
			capturing = null;
		}
		if (model == null)
		{
			return;
		}
		long shape = Learning.shape(actor, model);
		track.shape = shape;
		long key = key(track.animationId, shape);
		if (settled(key))
		{
			return;
		}
		boolean workedAhead = unchecked.contains(key);
		Poses.Outcome outcome = workedAhead ? poses.check(actor, track.animationId, model, track.frame)
			: poses.lesson(actor, track.animationId, lengths, model, shape, track.frame);
		if (outcome != null && outcome.lesson == null)
		{
			// an action's first keyframe may still be the pose animation's (its start delay): not counted
			boolean first = track.action && track.frame == 0 && outcome.again;
			int tried = first ? tries.getOrDefault(key, 0) : tries.merge(key, 1, Integer::sum);
			if (!outcome.again || tried >= TRIES)
			{
				tries.remove(key);
				unworkable.put(key, outcome.why);
				// one worked out ahead that is not what the client draws
				unchecked.remove(key);
				Learning.Learnt gone = learnt.remove(key);
				learntBytes -= gone == null ? 0 : gone.bytes;
				yours.remove(key);
			}
			return;
		}
		tries.remove(key);
		if (actor == local && track.action)
		{
			known(track.animationId);
		}
		if (workedAhead)
		{
			unchecked.remove(key);
			return;
		}
		Learning.Lesson lesson = outcome.lesson;
		// no keyframe table: the client's own blend is known, and with it where it goes wrong (see blends)
		lesson.yours = actor == local;
		finish(key, lesson);
	}

	/**
	 * Works the local player's animations out before they play: standing, walking and running, and the actions played
	 * before with the weapon worn (see known), as soon as its look changes (logging in, other gear). Working one out
	 * takes longer than a frame (10 to 50 ms, most of it off the client thread: see finish), so one that first shows
	 * unseen is the client's for its first frame or two; these are ready. One a frame: the poses are made on the client
	 * thread.
	 */
	private void workAhead()
	{
		Track track = local == null ? null : tracks.get(local);
		PlayerComposition composition = local == null ? null : local.getPlayerComposition();
		if (track == null || track.mode != Smoothing.KEYFRAMES || composition == null)
		{
			return;
		}
		long look = (31L * Arrays.hashCode(composition.getEquipmentIds()) + Arrays.hashCode(composition.getColors())) * 31
			+ composition.getGender();
		if (look != aheadLook)
		{
			aheadLook = look;
			aheadWaits = 0;
			ahead.clear();
			Set<Integer> wanted = new LinkedHashSet<>(Arrays.asList(local.getIdlePoseAnimation(), local.getWalkAnimation(),
				local.getRunAnimation()));
			wanted.addAll(knownActions.getOrDefault(composition.getEquipmentId(KitType.WEAPON), Set.of()));
			wanted.remove(-1);
			ahead.addAll(wanted);
		}
		Integer next = ahead.poll();
		int[] lengths = next == null ? null : lengths(next);
		if (lengths == null || lengths.length < 2)
		{
			return;
		}
		long shape = poses.shapeAhead(local, next);
		if (shape == 0)
		{
			if (poses.missing() && aheadWaits++ < AHEAD_WAITS)
			{
				ahead.add(next);
			}
			return;
		}
		long key = key(next, shape);
		if (learnt.containsKey(key) || learning.contains(key) || unworkable.containsKey(key))
		{
			return;
		}
		Learning.Lesson lesson = poses.ahead(local, next, lengths);
		if (lesson == null)
		{
			return;
		}
		lesson.yours = true;
		unchecked.add(key);
		finish(key, lesson);
	}

	/** An action the local player plays with the weapon worn: worked out ahead from now on (see workAhead), also next session. */
	private void known(int animationId)
	{
		PlayerComposition composition = local.getPlayerComposition();
		int weapon = composition == null ? -1 : composition.getEquipmentId(KitType.WEAPON);
		Set<Integer> actions = knownActions.remove(weapon);
		actions = actions == null ? new LinkedHashSet<>() : actions;
		knownActions.put(weapon, actions);
		boolean added = !actions.remove(animationId);
		actions.add(animationId);
		if (!added)
		{
			return;
		}
		if (actions.size() > KNOWN_PER_WEAPON)
		{
			actions.remove(actions.iterator().next());
		}
		if (knownActions.size() > KNOWN_WEAPONS)
		{
			knownActions.remove(knownActions.keySet().iterator().next());
		}
		configManager.setConfiguration(KeyframeSmoothingConfig.GROUP, KNOWN_ACTIONS, knownActions(knownActions));
	}

	/** The known actions as kept in the settings (see KNOWN_ACTIONS). */
	static String knownActions(Map<Integer, Set<Integer>> known)
	{
		StringBuilder out = new StringBuilder();
		for (Map.Entry<Integer, Set<Integer>> weapon : known.entrySet())
		{
			out.append(out.length() == 0 ? "" : ";").append(weapon.getKey()).append(':');
			String between = "";
			for (int animationId : weapon.getValue())
			{
				out.append(between).append(animationId);
				between = ",";
			}
		}
		return out.toString();
	}

	/** The known actions read back; what can't be read is left out. */
	static Map<Integer, Set<Integer>> knownActions(String text)
	{
		Map<Integer, Set<Integer>> known = new LinkedHashMap<>();
		for (String weapon : text == null ? new String[0] : text.split(";"))
		{
			String[] pair = weapon.split(":");
			try
			{
				Set<Integer> actions = new LinkedHashSet<>();
				for (String animationId : pair.length == 2 ? pair[1].split(",") : new String[0])
				{
					actions.add(Integer.parseInt(animationId.trim()));
				}
				if (!actions.isEmpty())
				{
					known.put(Integer.parseInt(pair[0].trim()), actions);
				}
			}
			catch (NumberFormatException e)
			{
				// not ours: left out
			}
		}
		return known;
	}

	/** Makes a lesson's spline (see Learning.learn), off the client thread. */
	private void finish(long key, Learning.Lesson lesson)
	{
		if (lesson.ready() == 0)
		{
			return;
		}
		learning.add(key);
		learner.execute(() ->
		{
			Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
			try
			{
				Learning.Learnt result = Learning.learn(lesson);
				clientThread.invokeLater(() ->
				{
					learning.remove(key);
					if (unworkable.containsKey(key))
					{
						// worked out ahead, and found not to be what the client draws meanwhile
						return;
					}
					Learning.Learnt before = learnt.put(key, result);
					learntBytes += result.bytes - (before == null ? 0 : before.bytes);
					if (lesson.yours)
					{
						yours.add(key);
					}
					// the least recently drawn go first while over the count or the memory; yours only when nothing else
					// is left (in a crowd the others' fill the store within a minute, and yours would be learnt anew
					// every time)
					while (learnt.size() > 1 && (learnt.size() > MAX_LEARNT || learntBytes > LEARNT_BYTES))
					{
						long gone = forgetFirst(learnt.keySet(), yours);
						learntBytes -= learnt.remove(gone).bytes;
						yours.remove(gone);
					}
				});
			}
			catch (RuntimeException e)
			{
				// stays in learning: not tried again this session
				log.debug("Could not work out animation {}", lesson.animationId, e);
			}
		});
	}

	/** Of the keys from least to most recently used, the one to forget first: the eldest not yours, else the eldest. */
	static long forgetFirst(Collection<Long> byUse, Set<Long> yours)
	{
		for (long key : byUse)
		{
			if (!yours.contains(key))
			{
				return key;
			}
		}
		return byUse.iterator().next();
	}

	// ---- the filter ----

	/** The filter. Called on the client thread and on the GPU plugin's render threads: read only. */
	private boolean shouldBlend(int animationId)
	{
		Thread thread = Thread.currentThread();
		if (capturing == thread)
		{
			return false;
		}
		return withOthers(animationId, answering == thread && animationId == answeringId ? answer : decision(animationId));
	}

	/** Our answer, and if that is to blend, the previous filter's (see shouldBlend). */
	private boolean withOthers(int animationId, boolean blend)
	{
		IntPredicate other = previous;
		boolean[] asking = askingPrevious.get();
		if (!blend || other == null || asking[0])
		{
			// asking[0]: a filter that wraps ours asked us back
			return blend;
		}
		asking[0] = true;
		try
		{
			return other.test(animationId);
		}
		catch (RuntimeException e)
		{
			log.debug("Another animation filter failed; leaving it out", e);
			previous = null;
			return true;
		}
		finally
		{
			asking[0] = false;
		}
	}

	private boolean decision(int animationId)
	{
		Snapshot now = snapshot;
		Boolean decided = now.decisions.get(animationId);
		if (decided != null)
		{
			return decided;
		}
		if (now.objects == now.objectExceptions.contains(animationId))
		{
			return false;
		}
		// objects and effects: blended unless one showing it is at a glitch now (see decideEffects)
		Boolean shown = now.effects.get(animationId);
		if (shown != null)
		{
			return shown;
		}
		// not seen: the filter doesn't say which keyframe it shows, so leave out animations with glitches
		KeyframeTable keyframes = table;
		return keyframes == null || !keyframes.hasGlitches(animationId);
	}

	// ---- for the drawer (client thread) ----

	Track track(Actor actor)
	{
		return tracks.get(actor);
	}

	Settings settings()
	{
		return settings;
	}

	Player localPlayer()
	{
		return local;
	}

	/** Whether the game starts the animation over at its first keyframe when it sends it again while it plays (chopping, picking). */
	boolean restartsWhenSent(int animationId)
	{
		AnimationInfo info = info(animationId);
		return info != null && info.restartsWhenSent;
	}

	/** Whether the client blends this animation in the frame being drawn: what the filter answers, others' filters too. */
	boolean blends(int animationId)
	{
		return shouldBlend(animationId);
	}

	/** As blends, for a model built with our answer own (see remodel). */
	boolean blends(int animationId, boolean own)
	{
		return withOthers(animationId, own);
	}

	/** Our answer for an animation this frame, without others' filters: the one everything showing it shares. */
	boolean decides(int animationId)
	{
		return decision(animationId);
	}

	/**
	 * Builds something's model again (client thread), the filter answering blend for one animation and as usual for the
	 * rest: for one whose keyframe should be blended otherwise than the others showing that animation.
	 */
	Model remodel(Renderable renderable, int animationId, boolean blend)
	{
		answeringId = animationId;
		answer = blend;
		answering = Thread.currentThread();
		try
		{
			return renderable.getModel();
		}
		finally
		{
			answering = null;
		}
	}

	Learning.Learnt learnt(int animationId, long shape)
	{
		return learnt.get(key(animationId, shape));
	}

	/** Ticks the character has shown its current keyframe, with the part of a tick since the client's last cycle. */
	double ticksInto(Track track)
	{
		return Math.max(0, client.getGameCycle() - track.startCycle) + cyclePart();
	}

	/**
	 * When the local player's own hitsplat comes in one of its attacks (ticks from the animation's start, see
	 * onHitsplatApplied), or NaN before one came.
	 */
	double hitAt(int animationId)
	{
		HitTimes times = hitTimes.get(animationId);
		return times == null ? Double.NaN : times.median();
	}

	/** The animation's keyframe lengths in ticks, or null for one that never blends (skeletal) or can't be loaded. */
	int[] lengths(int animationId)
	{
		AnimationInfo info = info(animationId);
		return info == null ? null : info.lengths;
	}

	/**
	 * Whether an animation strikes (see STRIKE_PRIORITY): an attack, not blocking or eating while a target is set. Read
	 * from the game's own definition, so it holds the first time an attack shows.
	 */
	boolean strikes(int animationId)
	{
		AnimationInfo info = info(animationId);
		return info != null && info.strikes;
	}

	void failed(String what, RuntimeException e)
	{
		if (!warned)
		{
			warned = true;
			log.warn("Keyframe Smoothing: {} failed; drawing on without it where it fails", what, e);
		}
	}

	private AnimationInfo info(int animationId)
	{
		if (animationId < 0)
		{
			return null;
		}
		if (!animations.containsKey(animationId))
		{
			Animation animation = client.loadAnimation(animationId);
			IndexDataBase configs = client.getIndexConfig();
			byte[] definition = configs == null ? null : configs.loadData(SEQUENCE_CONFIG, animationId);
			animations.put(animationId, animation == null ? null
				: new AnimationInfo(animation, table.get(animationId), forcedPriority(definition)));
		}
		return animations.get(animationId);
	}

	static long key(int animationId, long shape)
	{
		return shape * 0x9E3779B97F4A7C15L + animationId;
	}

	// ---- the local player's weapon ----

	/**
	 * The weapon's faces in a model: from face start, count faces; the vertices they use (and per face its three corners'
	 * places among them); and the weapon alone as a model of its own to draw its ghosts with, with per vertex of that
	 * model its place in vertices (-1 for none).
	 */
	static final class WeaponRun
	{
		final int start;
		final int count;
		final int[] vertices;
		final int[] corners;
		final Model ghost;
		final int[] ghostSlots;
		/**
		 * The faces (from the run's start) the weapon's own model shows in a plain colour where the player's model has a
		 * texture, and that model's own colours 1, 2 and 3 of them (see weaponModel); null: none.
		 */
		final int[][] plain;

		WeaponRun(int start, int count, int[] vertices, int[] a, int[] b, int[] c, Model ghost, int[] ghostSlots)
		{
			this(start, count, vertices, a, b, c, ghost, ghostSlots, null);
		}

		WeaponRun(int start, int count, int[] vertices, int[] a, int[] b, int[] c, Model ghost, int[] ghostSlots, int[][] plain)
		{
			this.plain = plain;
			this.start = start;
			this.count = count;
			this.vertices = vertices;
			corners = new int[3 * count];
			for (int f = 0; f < count; f++)
			{
				corners[3 * f] = Arrays.binarySearch(vertices, a[start + f]);
				corners[3 * f + 1] = Arrays.binarySearch(vertices, b[start + f]);
				corners[3 * f + 2] = Arrays.binarySearch(vertices, c[start + f]);
			}
			this.ghost = ghost;
			this.ghostSlots = ghostSlots;
		}

		/**
		 * Puts the weapon model's own colours (which: 1, 2 or 3) on the faces it shows plain, where the player's model's
		 * colours were copied in: those are a textured face's, its light only. A hidden face (-2) stays hidden.
		 */
		void own(int[] colours, int which)
		{
			for (int i = 0; plain != null && i < plain[0].length; i++)
			{
				int f = plain[0][i];
				if (colours[f] != -2)
				{
					colours[f] = plain[which][i];
				}
			}
		}
	}

	/** Where the local player's weapon is in its drawn model, or null. */
	WeaponRun weaponRun(Model model, int animationId)
	{
		Player player = local;
		PlayerComposition composition = player == null ? null : player.getPlayerComposition();
		if (composition == null)
		{
			return null;
		}
		int item = composition.getEquipmentId(KitType.WEAPON);
		// a skill's animation puts its tool in the hand, from the inventory (or the hand's own weapon away)
		AnimationInfo info = info(animationId);
		if (info != null && info.rightHand != -1)
		{
			item = info.rightHand == EMPTIED_HAND ? -1 : info.rightHand;
		}
		if (item < 0)
		{
			return null;
		}
		// the client's face arrays are shared by every frame of the same model: found once per model and weapon
		int[] a = model.getFaceIndices1();
		if (a == weaponRunFaces && item == weaponRunItem)
		{
			return weaponRun;
		}
		boolean female = composition.getGender() == 1;
		int start = facesBefore(composition, female);
		WeaponFinder.Faces weapon = weaponFaces(item, female);
		if (start < 0 || weapon == null)
		{
			// models not loaded yet: tried again next frame
			return null;
		}
		weaponRun = null;
		int faces = model.getFaceCount();
		WeaponFinder.Faces drawn = new WeaponFinder.Faces(Arrays.copyOf(a, faces), Arrays.copyOf(model.getFaceIndices2(), faces),
			Arrays.copyOf(model.getFaceIndices3(), faces));
		start = WeaponFinder.find(drawn, start, weapon);
		if (start >= 0)
		{
			Set<Integer> used = new HashSet<>();
			for (int f = start; f < start + weapon.count(); f++)
			{
				used.add(drawn.a[f]);
				used.add(drawn.b[f]);
				used.add(drawn.c[f]);
			}
			int[] vertices = used.stream().mapToInt(Integer::intValue).sorted().toArray();
			Model ghost = weaponModel(item, female);
			int[] slots = ghost == null || ghost.getFaceCount() != weapon.count() ? null : ghostSlots(ghost, drawn, start, vertices);
			weaponRun = new WeaponRun(start, weapon.count(), vertices, drawn.a, drawn.b, drawn.c, slots == null ? null : ghost,
				slots, slots == null ? null : weaponPlain.get(weaponKey(item, female)));
		}
		// kept only once made: when making it fails, it is tried again
		weaponRunFaces = a;
		weaponRunItem = item;
		return weaponRun;
	}

	/** Faces the client puts before the weapon's: those of what is worn on the head, back and neck; -1 while not loaded. */
	int facesBefore(PlayerComposition composition, boolean female)
	{
		int faces = 0;
		for (KitType slot : BEFORE_WEAPON)
		{
			int item = composition.getEquipmentId(slot);
			ModelData[] parts = item < 0 ? new ModelData[0] : wornParts(item, female);
			if (parts == null)
			{
				return -1;
			}
			for (ModelData part : parts)
			{
				faces += part.getFaceCount();
			}
		}
		return faces;
	}

	/** Per vertex of the weapon's own model, its place among the drawn model's weapon vertices: by the faces' corners. */
	private static int[] ghostSlots(Model ghost, WeaponFinder.Faces drawn, int start, int[] vertices)
	{
		int[] slots = new int[ghost.getVerticesCount()];
		Arrays.fill(slots, -1);
		int[][] own = {ghost.getFaceIndices1(), ghost.getFaceIndices2(), ghost.getFaceIndices3()};
		int[][] there = {drawn.a, drawn.b, drawn.c};
		for (int f = 0; f < ghost.getFaceCount(); f++)
		{
			for (int corner = 0; corner < 3; corner++)
			{
				int v = own[corner][f];
				if (slots[v] < 0)
				{
					slots[v] = Arrays.binarySearch(vertices, there[corner][start + f]);
				}
			}
		}
		return slots;
	}

	/** The weapon's faces as the client joins them into a player, from the item's definition and models; null if not loaded. */
	WeaponFinder.Faces weaponFaces(int item, boolean female)
	{
		long key = weaponKey(item, female);
		if (weapons.containsKey(key))
		{
			return weapons.get(key);
		}
		ModelData[] parts = wornParts(item, female);
		if (parts == null)
		{
			return null;
		}
		WeaponFinder.Faces faces = null;
		if (parts.length > 0)
		{
			WeaponFinder.Joiner joiner = new WeaponFinder.Joiner();
			for (ModelData model : parts)
			{
				joiner.add(model.getVerticesCount(), model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
					model.getFaceCount(), model.getFaceIndices1(), model.getFaceIndices2(), model.getFaceIndices3());
			}
			faces = joiner.faces();
		}
		weapons.put(key, faces != null && faces.count() > 0 ? faces : null);
		return weapons.get(key);
	}

	/**
	 * The weapon alone as a model of its own, faces in the order they have in a player: the renderer draws a player's
	 * model see-through only when it has transparencies, which players rarely do, so ghosts are drawn with this one
	 * (made with a transparency per face). Null when it can't be made; not loaded yet: null and tried again.
	 */
	private Model weaponModel(int item, boolean female)
	{
		long key = weaponKey(item, female);
		if (weaponModels.containsKey(key))
		{
			return weaponModels.get(key);
		}
		ModelData[] parts = wornParts(item, female);
		if (parts == null)
		{
			return null;
		}
		Model model = null;
		if (parts.length > 0)
		{
			// copies: the loaded models may be the client's cached ones
			ModelData joined = parts.length == 1 ? parts[0].shallowCopy() : client.mergeModels(parts, parts.length);
			joined = joined.cloneVertices().cloneColors().cloneTransparencies(true);
			// in the item's own colours and textures, as the player wears it (renderers that light it themselves read these)
			ItemComposition definition = client.getItemDefinition(item);
			short[] from = definition.getColorToReplace(), to = definition.getColorToReplaceWith();
			for (int i = 0; from != null && to != null && i < Math.min(from.length, to.length); i++)
			{
				joined = joined.recolor(from[i], to[i]);
			}
			from = definition.getTextureToReplace();
			to = definition.getTextureToReplaceWith();
			if (from != null && to != null && from.length > 0)
			{
				// only then: cloneTextures throws on a model without textures, as most weapons are
				joined = joined.cloneTextures();
			}
			for (int i = 0; from != null && to != null && i < Math.min(from.length, to.length); i++)
			{
				joined = joined.retexture(from[i], to[i]);
			}
			// a face with a texture gets a plain colour instead, the texture's own: a renderer can draw a texture solid
			// whatever the face's transparency (117 HD its lava, the orb of a lava battlestaff), and then every ghost of
			// it shows solid: a row of dots along the swing that stays until the trail is gone
			short[] textures = joined.getFaceTextures();
			int[] plain = new int[textures == null ? 0 : textures.length];
			int plains = 0;
			for (int f = 0; f < plain.length; f++)
			{
				if (textures[f] != -1)
				{
					plain[plains++] = f;
				}
			}
			if (plains > 0)
			{
				joined = joined.cloneTextures();
				textures = joined.getFaceTextures();
				short[] colours = joined.getFaceColors();
				for (int i = 0; i < plains; i++)
				{
					colours[plain[i]] = (short) client.getTextureProvider().getDefaultColor(textures[plain[i]]);
					textures[plain[i]] = -1;
				}
			}
			model = joined.light();
			if (plains > 0 && model != null && model.getFaceColors1() != null && model.getFaceColors2() != null
				&& model.getFaceColors3() != null)
			{
				// its own colours of those faces, kept: the ghosts' colours are written over each frame
				int[][] own = new int[4][plains];
				for (int i = 0; i < plains; i++)
				{
					own[0][i] = plain[i];
					own[1][i] = model.getFaceColors1()[plain[i]];
					own[2][i] = model.getFaceColors2()[plain[i]];
					own[3][i] = model.getFaceColors3()[plain[i]];
				}
				weaponPlain.put(key, own);
			}
		}
		weaponModels.put(key, model != null && model.getFaceTransparencies() != null ? model : null);
		return weaponModels.get(key);
	}

	/** The models an item is worn as, in the order the client joins them; null while not loaded, empty for none. */
	private ModelData[] wornParts(int item, boolean female)
	{
		byte[] data = client.getIndexConfig().loadData(ITEM_CONFIG, item);
		WeaponFinder.WornModels worn = data == null ? null : WeaponFinder.wornModels(data);
		if (worn == null)
		{
			return new ModelData[0];
		}
		int[] ids = Arrays.stream(worn.models(female)).filter(id -> id >= 0).toArray();
		ModelData[] parts = new ModelData[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			parts[i] = client.loadModelData(ids[i]);
			if (parts[i] == null)
			{
				return null;
			}
		}
		return parts;
	}

	private static long weaponKey(int item, boolean female)
	{
		return (long) item << 1 | (female ? 1 : 0);
	}

	/**
	 * The exception lists in the settings: names (weapons, NPCs, objects) that get the opposite of the setting for their
	 * kind. Their keys, and the menu's words for turning one on and off.
	 */
	enum Lists
	{
		SMOOTH("smoothExceptions", "Smooth", "Don't smooth"),
		GHOSTS("ghostExceptions", "Ghosts", "No ghosts");

		final String key, on, off;

		Lists(String key, String on, String off)
		{
			this.key = key;
			this.on = on;
			this.off = off;
		}
	}

	/** When an attack's own hitsplats came (ticks from its start): the median of the last HIT_SAMPLES, one a cycle early or late. */
	static final class HitTimes
	{
		private final double[] times = new double[HIT_SAMPLES];
		private int count;

		void add(double time)
		{
			times[count++ % times.length] = time;
		}

		double median()
		{
			double[] sorted = Arrays.copyOf(times, Math.min(count, times.length));
			Arrays.sort(sorted);
			return sorted[sorted.length / 2];
		}
	}

	/** The settings, read once when they change rather than on every draw. */
	static final class Settings
	{
		final boolean smoothMe, smoothPlayers, smoothNpcs, objects;
		final boolean ghosts, ghostsSpecOnly, smoothMenu, ghostMenu;
		/** Ghosts on the local player's attacks of each combat style. */
		final boolean ghostsMelee, ghostsRanged, ghostsMagic, ghostsSkilling;
		private final NameList[] excepted = new NameList[Lists.values().length];
		/** Blows exaggerated past their keyframes, else kept to them (see ActorDrawer.moment). */
		final boolean exaggerate;
		/** The local player's attacks land when their hitsplat comes (see ActorDrawer.synced). */
		final boolean syncHits;
		/** Skills' swings and repeated actions' blows are paced as attacks' (see ActorDrawer.smooth), else as the game has them. */
		final boolean skilling;
		/** A skill's swing cut in its wind-up strikes at once, landing with the game's sound (see ActorDrawer.held). */
		final boolean hitOnSound;
		/** How a blow is paced (ActorDrawer.STRIKE_EASE, EXAGGERATED_EASE). */
		final double strikeEase;

		Settings(KeyframeSmoothingConfig config)
		{
			this(config, config.exaggerate() ? ActorDrawer.EXAGGERATED_EASE : ActorDrawer.STRIKE_EASE);
		}

		Settings(KeyframeSmoothingConfig config, double strikeEase)
		{
			smoothMe = config.smoothMe();
			smoothPlayers = config.smoothPlayers();
			smoothNpcs = config.smoothNpcs();
			objects = config.objects();
			ghosts = config.ghosts();
			ghostsSpecOnly = config.ghostsSpecOnly();
			ghostsMelee = config.ghostsMelee();
			ghostsRanged = config.ghostsRanged();
			ghostsMagic = config.ghostsMagic();
			ghostsSkilling = config.ghostsSkilling();
			smoothMenu = config.smoothMenu();
			ghostMenu = config.ghostMenu();
			exaggerate = config.exaggerate();
			syncHits = config.syncHits();
			skilling = config.skilling();
			hitOnSound = config.hitOnSound();
			excepted[Lists.SMOOTH.ordinal()] = NameList.parse(config.smoothExceptions());
			excepted[Lists.GHOSTS.ordinal()] = NameList.parse(config.ghostExceptions());
			this.strikeEase = strikeEase;
		}

		/** Whether attacks of a combat style leave ghosts (an unknown style: yes). */
		boolean ghosts(CombatStyle style)
		{
			return style == CombatStyle.RANGED ? ghostsRanged : style == CombatStyle.MAGIC ? ghostsMagic : style != CombatStyle.MELEE || ghostsMelee;
		}

		NameList excepted(Lists list)
		{
			return excepted[list.ordinal()];
		}

		/** What the settings say about what a list is about, given whether they smooth the name's kind; a name on the list gets the opposite. */
		boolean bySettings(Lists list, boolean smoothed)
		{
			return list == Lists.SMOOTH ? smoothed : ghosts;
		}

		/** Whether a list's words are on the shift-right-click menu. */
		boolean menu(Lists list)
		{
			return list == Lists.SMOOTH ? smoothMenu : ghostMenu;
		}
	}

	/** What the filter reads; never changed once made. */
	private static final class Snapshot
	{
		static final Snapshot NONE = new Snapshot(Map.of(), Map.of(), true, Set.of());

		final Map<Integer, Boolean> decisions;
		/** Objects and effects: per animation, whether all that show it are at a keyframe that blends well. */
		final Map<Integer, Boolean> effects;
		final boolean objects;
		final Set<Integer> objectExceptions;

		Snapshot(Map<Integer, Boolean> decisions, Map<Integer, Boolean> effects, boolean objects, Set<Integer> objectExceptions)
		{
			this.decisions = decisions;
			this.effects = effects;
			this.objects = objects;
			this.objectExceptions = objectExceptions;
		}
	}

	/**
	 * An animation's forced priority from its definition in the game cache (5 when it has none, or it can't be read):
	 * the definition is a list of opcodes, each with its values, ended by 0.
	 */
	static int forcedPriority(byte[] data)
	{
		int priority = 5;
		if (data == null)
		{
			return priority;
		}
		try
		{
			for (int at = 0; at < data.length; )
			{
				int opcode = data[at++] & 0xFF;
				switch (opcode)
				{
					case 0:
						return priority;
					case 1:
						// keyframes: a count, then per keyframe its length, its frame and the frame's archive (2 bytes each)
						at += 2 + 6 * ((data[at] & 0xFF) << 8 | data[at + 1] & 0xFF);
						break;
					case 2:
					case 6:
					case 7:
						at += 2;
						break;
					case 3:
					case 17:
						at += 1 + (data[at] & 0xFF);
						break;
					case 4:
					case 19:
						break;
					case 5:
						priority = data[at++] & 0xFF;
						break;
					case 8:
					case 9:
					case 10:
					case 11:
					case 16:
						at++;
						break;
					case 12:
						at += 1 + 4 * (data[at] & 0xFF);
						break;
					case 13:
					case 15:
						at += 4;
						break;
					case 14:
						// sounds: a count, then per sound its keyframe (2 bytes) and the sound (6)
						at += 2 + 8 * ((data[at] & 0xFF) << 8 | data[at + 1] & 0xFF);
						break;
					case 18:
						while (data[at++] != 0)
						{
						}
						break;
					default:
						return 5;
				}
			}
		}
		catch (RuntimeException e)
		{
			// cut off
		}
		return priority;
	}

	private static final class AnimationInfo
	{
		/** Letters from the keyframe table, or null when it has none that fit. */
		final byte[] letters;
		/** The keyframe lengths (a copy), or null for skeletal animations, which never blend. */
		final int[] lengths;
		/** An attack's blows, see strikes. */
		final boolean strikes;
		/** What the animation puts in the right hand (an item id, EMPTIED_HAND for nothing), else -1. */
		final int rightHand;
		/** It starts over at its first keyframe when the game sends it again while it plays (its restart mode 1). */
		final boolean restartsWhenSent;

		AnimationInfo(Animation animation, byte[] letters, int priority)
		{
			rightHand = animation.getRightHandItem();
			restartsWhenSent = animation.getRestartMode() == 1;
			strikes = priority >= STRIKE_PRIORITY;
			int[] frameLengths = animation.isMayaAnim() ? null : animation.getFrameLengths();
			lengths = frameLengths == null ? null : frameLengths.clone();
			// a table made from an older game cache: only trust it when the keyframes still line up
			this.letters = letters != null && lengths != null && lengths.length == letters.length ? letters : null;
		}
	}

	/** The combat style of one of the local player's attacks, for the ghost setting of each style. */
	enum CombatStyle
	{
		MELEE, RANGED, MAGIC;

		/**
		 * The style the game's style names give the picked one (index): Ranging and Longrange are ranged, Casting is magic,
		 * and Defensive is defensive casting as a staff's sixth style or on a weapon that only casts (a powered staff);
		 * the rest, or none known, melee.
		 */
		static CombatStyle of(String[] names, int index)
		{
			if (names == null || index < 0 || index >= names.length || names[index] == null)
			{
				return MELEE;
			}
			switch (names[index].toLowerCase())
			{
				case "ranging":
				case "longrange":
					return RANGED;
				case "casting":
					return MAGIC;
				case "defensive":
					return index == 5 || castsOnly(names) ? MAGIC : MELEE;
				default:
					return MELEE;
			}
		}

		private static boolean castsOnly(String[] names)
		{
			boolean casts = false;
			for (String name : names)
			{
				String style = name == null ? "" : name.toLowerCase();
				if (style.equals("accurate") || style.equals("aggressive") || style.equals("controlled"))
				{
					return false;
				}
				casts |= style.equals("casting");
			}
			return casts;
		}
	}
}
