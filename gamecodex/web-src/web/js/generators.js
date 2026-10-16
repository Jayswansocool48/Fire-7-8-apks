/* Offline idea generators. Everything here runs on this computer from word lists;
   nothing is sent anywhere. Each generator returns a title, a short text to show,
   and the fields to fill in if the user adds it to their game. */
window.Codex = window.Codex || {};

(function () {
  const pick = (list) => list[Math.floor(Math.random() * list.length)];
  const pickN = (list, n) => {
    const copy = list.slice();
    const out = [];
    while (out.length < n && copy.length) out.push(copy.splice(Math.floor(Math.random() * copy.length), 1)[0]);
    return out;
  };
  const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
  const an = (word) => (/^[aeiou]/i.test(word) ? "an " : "a ") + word;

  const SYLLABLES = {
    fantasy: { start: ["Ae", "Bel", "Cor", "Dra", "El", "Fen", "Gal", "Ith", "Kael", "Lor", "Mor", "Nym", "Ory", "Ser", "Tha", "Val", "Wyn", "Ys", "Ar", "Ren"], mid: ["a", "e", "i", "o", "ae", "ia", "or", "an", "el", "ri", "th", "va"], end: ["dor", "wyn", "ra", "iel", "mir", "on", "eth", "is", "ael", "ion", "wen", "ric", "gar", "ys", "ane"] },
    scifi: { start: ["Zy", "Kor", "Vex", "Ax", "Nova", "Ty", "Qua", "Rho", "Syn", "Cyr", "Ori", "Jax", "Ven", "Hex", "Ion", "Kai"], mid: ["", "-", "o", "a", "ri", "x", "e"], end: ["on", "ix", "ra", "-7", "us", "a", "en", "ar", "-9", "is", "ex", "ion", "o"] },
    modern: { first: ["Maya", "Leo", "Jordan", "Riley", "Sam", "Nina", "Eli", "Zoe", "Marcus", "Ivy", "Theo", "Ava", "Jonah", "Priya", "Diego", "Hana", "Owen", "Lena", "Kofi", "Ruby"], last: ["Reyes", "Okafor", "Park", "Novak", "Hale", "Moreau", "Kim", "Silva", "Brooks", "Tanaka", "Ward", "Ivanova", "Cole", "Mendez", "Frost", "Abara"] },
    cute: { start: ["Pip", "Bun", "Mochi", "Tuff", "Bloop", "Nib", "Puff", "Sprout", "Wig", "Bean", "Pudd", "Fizz", "Doodle", "Momo", "Toot"], end: ["kin", "let", "ble", "bo", "py", "sy", "ers", "o", "ly", "s", "ie", "bean"] },
    dark: { start: ["Mal", "Vor", "Grim", "Mor", "Skar", "Nek", "Dra", "Zul", "Kra", "Thra", "Ash", "Vel", "Gor"], mid: ["", "a", "o", "u", "ae"], end: ["goth", "rax", "thul", "zar", "mord", "kai", "veth", "grim", "nox", "rath", "skul", "vane"] },
  };

  const TITLES = { fantasy: ["the Bold", "of the Ashen Vale", "Stormborn", "the Wanderer", "of House Varn", "the Twice-Cursed"], scifi: ["Unit", "Captain", "Dr.", "Agent", "Pilot", "Commander"], dark: ["the Hollow", "the Devourer", "Who Waits Below", "the Unbound", "of the Black Spire"] };

  function name(style) {
    style = style || pick(["fantasy", "scifi", "modern", "cute", "dark"]);
    const s = SYLLABLES[style];
    if (style === "modern") return pick(s.first) + " " + pick(s.last);
    if (style === "cute") return pick(s.start) + pick(s.end);
    const n = pick(s.start) + (Math.random() < 0.5 ? pick(s.mid) : "") + pick(s.end);
    if (style === "scifi" && Math.random() < 0.3) return pick(TITLES.scifi) + " " + n;
    if ((style === "fantasy" || style === "dark") && Math.random() < 0.25) return n + " " + pick(TITLES[style]);
    return n;
  }

  const KINDS = {
    fantasy: ["elf ranger", "dwarf blacksmith", "human knight", "orc shaman", "fox spirit", "half-giant monk", "goblin tinkerer", "witch", "bard", "dragon in human form", "paladin", "druid"],
    scifi: ["android medic", "salvage pilot", "rogue AI in a drone", "clone soldier", "alien diplomat", "space trucker", "cyborg hacker", "terraformer", "bounty hunter", "station mechanic"],
    modern: ["detective", "skater", "night-shift nurse", "street magician", "food truck chef", "streamer", "lifeguard", "museum guard", "courier", "high school janitor"],
    cute: ["mushroom sprite", "baby slime", "frog knight", "cloud puppy", "tea kettle golem", "moss bunny", "pocket dragon", "snail mail carrier", "candle ghost"],
    dark: ["plague doctor", "cursed knight", "bone witch", "fallen angel", "vampire noble", "grave keeper", "hollow king", "cult leader", "eldritch scholar"],
  };
  const TRAITS = ["loyal", "reckless", "curious", "sarcastic", "gentle", "stubborn", "cowardly", "ambitious", "kind", "secretive", "proud", "playful", "paranoid", "honest", "greedy", "calm", "impulsive", "patient", "cheerful", "grumpy", "brave", "lazy", "clever", "jealous", "protective", "dramatic", "shy", "fearless", "superstitious", "optimistic"];
  const GOALS = ["find their missing sibling", "pay off a debt to the wrong people", "become the strongest in the land", "break the curse on their village", "get home", "prove their old mentor wrong", "open a little shop", "destroy the machine that made them", "win back a lost love", "protect the last seed of the world tree", "uncover who framed them", "be remembered", "escape their family's legacy", "map the edge of the world"];
  const FLAWS = ["can't say no", "trusts the wrong people", "is afraid of the dark", "lies when nervous", "holds grudges forever", "never asks for help", "can't stop gambling", "is terrified of water", "talks too much", "can't read", "hates magic", "freezes under pressure", "is too proud to retreat"];
  const SECRETS = ["is secretly royalty", "caused the disaster that started it all", "is working for the villain", "has already died once", "is the villain's child", "made a deal with something ancient", "stole their name from a dead friend", "can hear the dungeon think", "is from the future", "isn't human"];
  const LOOKS = ["a scar across one eye", "a coat two sizes too big", "glowing tattoos", "a mechanical arm", "one horn broken off", "a hat with too many feathers", "mismatched boots", "a lantern that never goes out", "a patched-up cape", "a mask they never remove", "freckles and a gap-toothed grin", "armor covered in moss"];
  const QUOTES = ["I didn't come this far to stop now.", "Rules are more like suggestions.", "Every coin has a story. Most of them are sad.", "Run first, ask questions never.", "You'll thank me later. Probably.", "The old ways are dead. Good.", "If it moves, I can fix it. If it doesn't, I can make it.", "Stay close. It gets weird from here.", "I've seen worse. Not much worse.", "Do you hear that? No? Good."];
  const ROLES = ["Protagonist", "Antagonist", "Ally", "Companion", "Mentor", "Rival", "NPC", "Merchant", "Enemy", "Boss"];
  const ABILITIES = ["double jump", "grappling hook", "summon a familiar", "turn invisible for 3 seconds", "parry and counter", "heal allies nearby", "talk to animals", "slow time", "throw anything", "phase through walls", "fire chain lightning", "build a turret", "teleport to a marker", "charm shopkeepers", "climb any surface", "eat enemies to copy them"];

  function character(style) {
    style = style || pick(["fantasy", "scifi", "modern", "cute", "dark"]);
    const n = name(style);
    const kind = pick(KINDS[style]);
    const traits = pickN(TRAITS, 3);
    const goal = pick(GOALS);
    const flaw = pick(FLAWS);
    const secret = pick(SECRETS);
    const look = pick(LOOKS);
    const role = pick(ROLES);
    return {
      section: "characters",
      title: n,
      text: `${cap(an(traits[0]))} ${kind} who wants to ${goal}, but ${flaw}. Secretly, ${n.split(" ")[0]} ${secret}.`,
      fields: {
        name: n, role, kind: cap(kind), traits, goal: cap(goal) + ".", flaw: cap(flaw) + ".",
        look: "Known for " + look + ".",
        backstory: `Secret: ${n.split(" ")[0]} ${secret}.`,
        abilities: pickN(ABILITIES, 2),
        quotes: [pick(QUOTES)],
        stats: [["Health", rnd(3, 9)], ["Attack", rnd(2, 9)], ["Defense", rnd(2, 9)], ["Speed", rnd(2, 9)], ["Magic", rnd(1, 9)]],
      },
    };
  }

  function rnd(a, b) { return a + Math.floor(Math.random() * (b - a + 1)); }

  const PROP_ADJ = ["Rusted", "Whispering", "Gilded", "Shattered", "Ancient", "Humming", "Frozen", "Cursed", "Lucky", "Bottomless", "Clockwork", "Bloodstained", "Glowing", "Tiny", "Hollow", "Singing", "Patched", "Forbidden", "Weightless", "Mossy"];
  const PROP_MAT = ["iron", "bone", "glass", "obsidian", "oak", "brass", "crystal", "silver", "coral", "copper", "jade", "starmetal", "leather", "paper", "ivory"];
  const PROPS = [
    ["Sword", "Weapon"], ["Dagger", "Weapon"], ["Bow", "Weapon"], ["Hammer", "Weapon"], ["Staff", "Weapon"], ["Pistol", "Weapon"], ["Shield", "Armor"], ["Helmet", "Armor"], ["Gauntlet", "Armor"],
    ["Lantern", "Tool"], ["Compass", "Tool"], ["Key", "Key item"], ["Music box", "Key item"], ["Map", "Key item"], ["Potion", "Consumable"], ["Bomb", "Consumable"], ["Tonic", "Consumable"],
    ["Coin", "Currency"], ["Idol", "Collectible"], ["Feather", "Collectible"], ["Mask", "Collectible"], ["Throne", "Furniture"], ["Chest", "Furniture"], ["Mirror", "Furniture"], ["Hoverboard", "Vehicle"], ["Rowboat", "Vehicle"],
  ];
  const PROP_POWERS = ["glows near hidden doors", "remembers the last person who held it", "gets heavier when you lie", "can be thrown and returns", "opens any lock once, then breaks", "whispers enemy positions", "doubles your speed but drains health", "lets you breathe underwater", "reveals invisible platforms", "turns enemies to friends for 10 seconds", "can store one spell", "makes merchants lower their prices", "shows a memory when touched", "only works at night"];
  const RARITIES = ["Common", "Common", "Uncommon", "Uncommon", "Rare", "Epic", "Legendary"];

  function prop() {
    const [noun, type] = pick(PROPS);
    const adj = pick(PROP_ADJ);
    const mat = pick(PROP_MAT);
    const power = pick(PROP_POWERS);
    const title = `${adj} ${cap(mat)} ${noun.split(" ").map(cap).join(" ")}`;
    const stats = type === "Weapon" ? [["Damage", rnd(2, 9)], ["Weight", rnd(1, 8)], ["Value", rnd(1, 9)]] : [["Weight", rnd(1, 6)], ["Value", rnd(1, 9)]];
    return {
      section: "props",
      title,
      text: `${cap(an(noun.toLowerCase()))} made of ${mat} that ${power}.`,
      fields: { name: title, type, rarity: pick(RARITIES), materials: [cap(mat)], description: `${cap(an(adj.toLowerCase()))} ${noun.toLowerCase()} made of ${mat}.`, use: `It ${power}.`, stats },
    };
  }

  const WORLDS = ["a floating archipelago", "a city built on a sleeping giant", "a dying space station", "a kingdom where it never stops raining", "an endless shopping mall", "a haunted ski resort", "a world inside a snow globe", "a desert of glass", "a sunken metropolis", "a school for monster hunters", "a train that never stops", "the last library on Earth", "a cozy village on a volcano", "a dream that's falling apart"];
  const HEROES = ["a retired hero", "a cowardly courier", "a newly built robot", "a cursed chef", "a ghost who doesn't know they're dead", "twin thieves", "a lighthouse keeper", "a disgraced knight", "a kid with a magic sketchbook", "a mercenary's dog", "a tiny frog with a big sword", "the villain's former sidekick"];
  const QUESTS = ["recover a stolen heart", "deliver one last letter", "wake the sleeping gods", "win a tournament nobody survives", "repair the sun", "find a cure in seven days", "map the shifting caves", "rebuild their ruined town", "escape before the tide returns", "collect the scattered pages of a spellbook"];
  const THREATS = ["the moon falls", "the clock tower strikes thirteen", "a rival guild gets there first", "their memories fade completely", "the empire's war machine wakes", "the storm swallows the island", "winter never ends", "the dungeon finishes digging upward"];
  const THEMES = ["Trust", "Letting go", "Found family", "Power and its cost", "Home", "Memory", "Redemption", "Freedom", "Grief", "Growing up", "Greed", "Hope"];

  function premise() {
    const world = pick(WORLDS), hero = pick(HEROES), quest = pick(QUESTS), threat = pick(THREATS);
    const text = `In ${world}, ${hero} must ${quest} before ${threat}.`;
    const theme = pick(THEMES);
    return {
      section: "story", title: "Story premise", text,
      fields: {
        name: cap(quest.replace(/^(a|an|the) /, "")), kind: "Storyline", summary: text, theme,
        acts: [
          { title: "Setup", text: `Show ${hero} at home in ${world}. Something breaks the routine.` },
          { title: "Confrontation", text: `The journey to ${quest} gets harder; allies are tested.` },
          { title: "Resolution", text: `A final push, just before ${threat}.` },
        ],
      },
    };
  }

  const GIVERS = ["a nervous innkeeper", "a talking crow", "the town's oldest farmer", "a mysterious hooded figure", "a child who lost their pet", "the captain of the guard", "a merchant with a broken cart", "a ghost in the well", "a lonely robot", "the local witch"];
  const TASKS = ["find a missing shipment", "clear the cellar of something that hums", "escort a goat across the bridge", "steal back a family ring", "deliver a cake without dropping it", "light the three beacons", "photograph a rare beast", "win a cooking contest", "catch a thief who can fly", "fix the town's clock", "collect 10 glowing mushrooms", "convince a dragon to move"];
  const HOOK_TWISTS = ["but the item is cursed", "but the giver is lying", "but the target wants to be found", "and a rival is doing the same quest", "but it's a trap to get you out of town", "but you have to do it at night", "but you can't use weapons", "and it turns out to be a test"];
  const QUEST_TYPES = ["Side", "Fetch", "Escort", "Hunt", "Puzzle"];

  function quest() {
    const giver = pick(GIVERS), task = pick(TASKS), twist = pick(HOOK_TWISTS);
    const text = `${cap(giver)} asks you to ${task}, ${twist}.`;
    return {
      section: "story", title: "Quest hook", text,
      fields: { name: cap(task), kind: "Quest", summary: text, questType: pick(QUEST_TYPES), objectives: [{ text: cap(task), done: false }, { text: "Report back to " + giver.replace(/^(a|an|the) /, "the "), done: false }] },
    };
  }

  const TWISTS = ["The mentor was the villain all along.", "The player has been fighting their own future self.", "The kingdom you're saving started the war.", "The final boss is the first friend you made.", "The whole world is a simulation, and it's shutting down.", "The 'monsters' are the original inhabitants.", "The hero died in the prologue; this is their afterlife.", "Your companion has been reporting your every move.", "The treasure was a seal keeping something in.", "The villain is trying to save everyone, just badly.", "Every time you rest, someone in town forgets you.", "The map is wrong on purpose."];

  function twist() {
    const t = pick(TWISTS);
    return { section: "story", title: "Plot twist", text: t, fields: { name: "Twist: " + t.split(" ").slice(0, 4).join(" ") + "…", kind: "Chapter", summary: t, twists: [t] } };
  }

  const BIOMES = ["Sunken library", "Neon rooftops", "Mushroom forest", "Frozen shipwreck", "Clockwork tower", "Lava foundry", "Floating ruins", "Overgrown subway", "Candy factory", "Bone desert", "Haunted mansion", "Crystal caves", "Sky harbor", "Abandoned theme park", "Coral palace", "Junkyard city"];
  const FEATURES = ["platforms that only appear in the dark", "rising water every 60 seconds", "a giant creature sleeping in the middle", "gravity that flips at switches", "conveyor belts everywhere", "mirrors that show hidden paths", "rooms that rotate", "a shop run by a ghost", "a race against a rival", "wind that pushes you around", "doors that need two players", "a maze that rearranges itself"];
  const HAZARDS = ["falling debris", "toxic fog", "patrolling sentries", "crumbling floors", "spikes", "swarming bats", "electric water", "lava geysers", "pressure plates", "snipers"];
  const OBJECTIVES = ["reach the top", "rescue the captured scout", "find the three keys", "survive five waves", "escape before collapse", "defeat the guardian", "restore power", "sneak past without being seen"];
  const MOODS = ["Eerie", "Cozy", "Frantic", "Mysterious", "Peaceful", "Tense", "Whimsical", "Grand", "Lonely"];

  function level() {
    const biome = pick(BIOMES), feature = pick(FEATURES), hazard = pick(HAZARDS), objective = pick(OBJECTIVES);
    const text = `${biome} with ${feature}. Watch out for ${hazard}. Goal: ${objective}.`;
    return {
      section: "levels", title: biome, text,
      fields: { name: biome, biome, objective: cap(objective) + ".", difficulty: rnd(2, 8), mood: pickN(MOODS, 2), layout: `Main feature: ${feature}.\nHazard: ${hazard}.` },
    };
  }

  const VERBS = ["Rewind time", "Swap places with any enemy", "Grow and shrink", "Paint platforms into existence", "Possess objects", "Link two objects with a rope", "Freeze anything you shoot", "Copy the last enemy you hit", "Switch between two worlds", "Throw your shadow", "Stack enemies as a ladder", "Charge a jump by standing still", "Plant seeds that become platforms", "Bounce off sound waves", "Carry fire to light the way"];
  const LIMITS = ["but only while standing still", "but it costs health", "but each use is permanent", "but only 3 times per level", "but enemies can do it too", "but it makes noise", "but only on beat with the music", "but you can't see while doing it", "but it drains your light", "and combos make it stronger"];
  const CATEGORIES = { "Rewind time": "Core loop", "Swap places with any enemy": "Combat", "Grow and shrink": "Movement", "Paint platforms into existence": "Movement", "Possess objects": "Puzzle", "Link two objects with a rope": "Puzzle", "Freeze anything you shoot": "Combat", "Copy the last enemy you hit": "Combat", "Switch between two worlds": "Puzzle", "Throw your shadow": "Movement", "Stack enemies as a ladder": "Puzzle", "Charge a jump by standing still": "Movement", "Plant seeds that become platforms": "Crafting", "Bounce off sound waves": "Movement", "Carry fire to light the way": "Puzzle" };

  function mechanic() {
    const verb = pick(VERBS), limit = pick(LIMITS);
    const text = `${verb}, ${limit}.`;
    return {
      section: "mechanics", title: verb, text,
      fields: { name: verb, category: CATEGORIES[verb] || "Core loop", status: "Idea", description: text, rules: [cap(limit) + "."], risks: ["Is it fun in the first 30 seconds?", "Can players break it?"] },
    };
  }

  const MASH = ["Roguelike", "Farming sim", "Rhythm game", "Racing game", "Deckbuilder", "Tower defense", "Dating sim", "Metroidvania", "Survival horror", "Puzzle platformer", "City builder", "Fighting game", "Cooking game", "Stealth game", "Bullet hell", "Pet sim", "Detective game"];
  const SETTINGS = ["underwater", "in space", "inside a computer", "in a haunted hotel", "in a tiny garden", "on a moving train", "in the Wild West", "in a cyberpunk city", "in a dream", "inside a giant's pocket", "in ancient Egypt", "at the end of the world"];

  function gameIdea() {
    const [a, b] = pickN(MASH, 2);
    const setting = pick(SETTINGS);
    const text = `${a} meets ${b.toLowerCase()}, ${setting}.`;
    return { section: null, title: "Game idea", text, fields: { pitch: text } };
  }

  Codex.Generators = {
    list: [
      { id: "character", label: "Character", section: "characters", run: character, styles: true },
      { id: "name", label: "Name only", section: "characters", run: (style) => { const n = name(style); return { section: "characters", title: n, text: "Use it for a character, a place or a boss.", fields: { name: n } }; }, styles: true },
      { id: "prop", label: "Prop", section: "props", run: prop },
      { id: "premise", label: "Story premise", section: "story", run: premise },
      { id: "quest", label: "Quest hook", section: "story", run: quest },
      { id: "twist", label: "Plot twist", section: "story", run: twist },
      { id: "mechanic", label: "Mechanic", section: "mechanics", run: mechanic },
      { id: "level", label: "Level", section: "levels", run: level },
      { id: "game", label: "Game idea", section: null, run: gameIdea },
    ],
    STYLES: [["fantasy", "Fantasy"], ["scifi", "Sci-fi"], ["modern", "Modern"], ["cute", "Cute"], ["dark", "Dark"]],
    name, character, prop, premise, quest, twist, mechanic, level, gameIdea,
  };
})();
