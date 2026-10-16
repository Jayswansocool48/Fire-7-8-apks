/* What each section of a game design holds. The editor builds its forms from this. */
window.Codex = window.Codex || {};

Codex.SECTIONS = [
  {
    id: "characters", name: "Characters", one: "character", color: "coral",
    icon: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm-7 9a7 7 0 0 1 14 0",
    empty: "Heroes, villains, shopkeepers and bosses. Add one, or roll a random character to start from.",
    fields: [
      { key: "role", label: "Role", type: "select", options: ["Protagonist", "Antagonist", "Ally", "Companion", "Mentor", "Rival", "NPC", "Merchant", "Enemy", "Boss"] },
      { key: "kind", label: "Species or class", type: "text", placeholder: "Elf ranger, scrap robot, cursed knight…" },
      { key: "age", label: "Age", type: "text", placeholder: "34, ancient, unknown" },
      { key: "look", label: "Appearance", type: "textarea", placeholder: "Silhouette, colors, clothes, what you notice first" },
      { key: "traits", label: "Personality traits", type: "tags", placeholder: "Type a trait and press Enter" },
      { key: "goal", label: "Goal", type: "textarea", rows: 2, placeholder: "What they want" },
      { key: "flaw", label: "Flaw or fear", type: "textarea", rows: 2 },
      { key: "backstory", label: "Backstory", type: "textarea", rows: 5 },
      { key: "abilities", label: "Abilities", type: "list", placeholder: "Add an ability" },
      { key: "stats", label: "Stats", type: "stats", defaults: ["Health", "Attack", "Defense", "Speed", "Magic"] },
      { key: "quotes", label: "Voice lines", type: "list", placeholder: "Something they'd say" },
      { key: "relations", label: "Relationships", type: "links", to: "characters", withNote: true, notePlaceholder: "Sister, sworn enemy…" },
      { key: "items", label: "Carries", type: "links", to: "props" },
    ],
  },
  {
    id: "props", name: "Props", one: "prop", color: "amber",
    icon: "M4 8l8-4 8 4-8 4-8-4zm0 0v8l8 4 8-4V8M12 12v8",
    empty: "Weapons, tools, potions, keys and furniture. Add one, or roll a random prop.",
    fields: [
      { key: "type", label: "Type", type: "select", options: ["Weapon", "Armor", "Tool", "Consumable", "Key item", "Collectible", "Furniture", "Vehicle", "Environment", "Currency"] },
      { key: "rarity", label: "Rarity", type: "select", options: ["Common", "Uncommon", "Rare", "Epic", "Legendary", "Unique"] },
      { key: "description", label: "Description", type: "textarea" },
      { key: "materials", label: "Materials", type: "tags", placeholder: "Iron, bone, glass…" },
      { key: "size", label: "Size", type: "select", options: ["Tiny", "Hand-held", "Two-handed", "Human-sized", "Large", "Huge"] },
      { key: "use", label: "How the player uses it", type: "textarea", rows: 3 },
      { key: "stats", label: "Stats", type: "stats", defaults: ["Damage", "Weight", "Value"] },
      { key: "found", label: "Found in", type: "links", to: "levels" },
      { key: "owner", label: "Owned by", type: "links", to: "characters" },
    ],
  },
  {
    id: "story", name: "Story & quests", one: "story entry", color: "violet",
    icon: "M5 4h10a4 4 0 0 1 4 4v12H9a4 4 0 0 1-4-4V4zm4 0v12",
    empty: "Your storyline, chapters, quests and dialogue. Add one, or roll a premise, a quest hook or a plot twist.",
    kinds: ["Storyline", "Chapter", "Quest", "Dialogue"],
    fields: [
      { key: "kind", label: "Kind", type: "select", options: ["Storyline", "Chapter", "Quest", "Dialogue"], always: true },
      { key: "summary", label: "Summary", type: "textarea", rows: 3, placeholder: "One or two sentences" },
      { key: "theme", label: "Theme", type: "text", only: ["Storyline"], placeholder: "Trust, loss, home…" },
      { key: "acts", label: "Acts", type: "beats", only: ["Storyline"], itemLabel: "Act" },
      { key: "twists", label: "Twists", type: "list", only: ["Storyline", "Chapter"], placeholder: "Add a twist" },
      { key: "order", label: "Chapter number", type: "number", only: ["Chapter"] },
      { key: "beats", label: "Story beats", type: "beats", only: ["Chapter"], itemLabel: "Beat" },
      { key: "questType", label: "Quest type", type: "select", only: ["Quest"], options: ["Main", "Side", "Fetch", "Escort", "Hunt", "Puzzle", "Daily"] },
      { key: "giver", label: "Quest giver", type: "links", to: "characters", only: ["Quest"], single: true },
      { key: "objectives", label: "Objectives", type: "checklist", only: ["Quest"] },
      { key: "rewards", label: "Rewards", type: "links", to: "props", only: ["Quest"] },
      { key: "rewardText", label: "Other rewards", type: "text", only: ["Quest"], placeholder: "250 XP, unlocks the east gate" },
      { key: "where", label: "Takes place in", type: "links", to: "levels", only: ["Chapter", "Quest", "Dialogue"] },
      { key: "cast", label: "Characters", type: "links", to: "characters", only: ["Storyline", "Chapter", "Dialogue"] },
      { key: "dialogue", label: "Conversation", type: "dialogue", only: ["Dialogue"] },
    ],
  },
  {
    id: "mechanics", name: "Mechanics", one: "mechanic", color: "teal",
    icon: "M12 3v3m0 12v3M3 12h3m12 0h3M6 6l2 2m8 8 2 2M6 18l2-2m8-8 2-2M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
    empty: "Rules, systems, controls and the numbers you tune. Add one, or roll a mechanic idea.",
    fields: [
      { key: "category", label: "Category", type: "select", options: ["Core loop", "Movement", "Combat", "Economy", "Progression", "Crafting", "Puzzle", "AI", "Camera", "UI", "Multiplayer"] },
      { key: "status", label: "Status", type: "select", options: ["Idea", "Prototyping", "Playable", "Polished", "Cut"] },
      { key: "description", label: "How it works", type: "textarea", rows: 4 },
      { key: "rules", label: "Rules", type: "list", placeholder: "Add a rule" },
      { key: "controls", label: "Controls", type: "pairs", left: "Input", right: "Action", placeholder: ["Space", "Jump"] },
      { key: "tuning", label: "Tuning values", type: "pairs", left: "Value", right: "Setting", placeholder: ["Jump height", "2.5 m"] },
      { key: "fun", label: "Why it's fun", type: "textarea", rows: 2 },
      { key: "risks", label: "Open questions", type: "list", placeholder: "What still needs testing" },
      { key: "uses", label: "Used by", type: "links", to: "characters" },
    ],
  },
  {
    id: "levels", name: "Levels", one: "level", color: "green",
    icon: "M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2V6zm6-2v14m6-12v14",
    empty: "Maps, areas and stages. Add one, or roll a random setting. Sketch the layout right on the level.",
    fields: [
      { key: "order", label: "Order", type: "number" },
      { key: "biome", label: "Setting", type: "text", placeholder: "Sunken library, neon rooftop…" },
      { key: "objective", label: "Player objective", type: "textarea", rows: 2 },
      { key: "difficulty", label: "Difficulty", type: "range", max: 10 },
      { key: "mood", label: "Mood", type: "tags", placeholder: "Eerie, cozy, frantic…" },
      { key: "layout", label: "Layout notes", type: "textarea", rows: 4, placeholder: "Start, path, secrets, the boss room" },
      { key: "enemies", label: "Enemies and characters", type: "links", to: "characters" },
      { key: "items", label: "Items here", type: "links", to: "props" },
      { key: "mechanics", label: "Mechanics introduced", type: "links", to: "mechanics" },
      { key: "next", label: "Leads to", type: "links", to: "levels" },
      { key: "music", label: "Music and sound", type: "text" },
    ],
  },
];

Codex.sectionById = (id) => Codex.SECTIONS.find((s) => s.id === id);

Codex.GENRES = ["Action", "Adventure", "RPG", "Platformer", "Puzzle", "Shooter", "Strategy", "Simulation", "Survival", "Horror", "Racing", "Sports", "Fighting", "Roguelike", "Metroidvania", "Visual novel", "Sandbox", "Rhythm"];
Codex.PLATFORMS = ["PC", "Linux", "Steam Deck", "Console", "Mobile", "Web", "VR", "Roblox"];
