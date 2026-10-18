/* A small example game so people can see how the pieces fit together. */
window.Codex = window.Codex || {};

Codex.sampleGame = function (newProject) {
  const id = () => Codex.uid("e");
  const wren = id(), moss = id(), witch = id(), lantern = id(), compass = id(), docks = id(), library = id(), light = id(), pull = id();
  const n1 = Codex.uid("n"), n2 = Codex.uid("n"), n3 = Codex.uid("n"), n4 = Codex.uid("n"), n5 = Codex.uid("n");
  const now = Date.now();
  return newProject({
    name: "Lantern Keeper",
    pitch: "A cozy puzzle adventure where a young lighthouse keeper carries the last living flame through a drowned town, using light to raise the sunken streets one by one.",
    genre: ["Adventure", "Puzzle"],
    platforms: ["PC", "Linux", "Steam Deck"],
    audience: "Cozy and puzzle players, ages 10+",
    artStyle: "Soft hand-painted 2.5D with warm light against cold water",
    palette: ["#1b2a41", "#324a5f", "#f4a259", "#f8e9a1", "#5b8e7d"],
    inspirations: "A Short Hike, Gris, Spiritfarer",
    entries: {
      characters: [
        { id: wren, created: now, name: "Wren", role: "Protagonist", kind: "Human lighthouse keeper", age: "15",
          look: "Oversized yellow raincoat, a lantern on a long pole, salt-stiff hair.",
          traits: ["curious", "stubborn", "kind"], goal: "Bring the town back above the water.", flaw: "Afraid of deep water, and never says so.",
          backstory: "Raised by the old keeper, who vanished the night the sea rose.",
          abilities: ["Lantern light", "Tide pull"], quotes: ["If there's a light, there's a way back."],
          stats: [["Health", 5], ["Attack", 2], ["Defense", 3], ["Speed", 6], ["Magic", 7]],
          relations: [{ id: moss, note: "Mentor" }, { id: witch, note: "Wants her lantern" }], items: [{ id: lantern }, { id: compass }], art: [] },
        { id: moss, created: now, name: "Old Mossback", role: "Mentor", kind: "Ancient sea tortoise", age: "About 300",
          look: "A shell covered in a tiny garden and one wind chime.", traits: ["patient", "forgetful", "wise"],
          goal: "Hand the flame to someone who'll keep it.", flaw: "Falls asleep mid-sentence.",
          abilities: ["Carry Wren across open water"], quotes: ["Slow is still moving, little light."],
          stats: [["Health", 9], ["Attack", 1], ["Defense", 10], ["Speed", 1], ["Magic", 5]], relations: [{ id: wren, note: "Student" }], art: [] },
        { id: witch, created: now, name: "The Tidewitch", role: "Boss", kind: "Spirit of the drowned bay",
          look: "A shape made of kelp and moonlight. Her lantern is cold and blue.", traits: ["lonely", "proud", "dramatic"],
          goal: "Keep the town under the water, where nothing changes.", flaw: "Can't stand the warmth of real flame.",
          backstory: "She was the town's first keeper. Secret: she raised the sea to stop the town from forgetting her.",
          stats: [["Health", 10], ["Attack", 7], ["Defense", 6], ["Speed", 4], ["Magic", 10]], relations: [{ id: wren, note: "Sees herself in her" }], art: [] },
      ],
      props: [
        { id: lantern, created: now, name: "Everlantern", type: "Key item", rarity: "Unique", description: "A brass storm lantern holding the last warm flame.",
          materials: ["Brass", "Glass"], size: "Hand-held", use: "Shine it on drowned things to lift them out of the water. Hold longer to lift heavier things.",
          stats: [["Weight", 3], ["Value", 10]], owner: [{ id: wren }], found: [{ id: docks }], art: [] },
        { id: compass, created: now, name: "Tide Compass", type: "Tool", rarity: "Rare", description: "Points to the nearest sunken landmark instead of north.",
          materials: ["Copper", "Pearl"], size: "Tiny", use: "Shows the way to the next puzzle on the map.", stats: [["Weight", 1], ["Value", 6]], found: [{ id: library }], art: [] },
      ],
      story: [
        { id: id(), created: now, name: "The Drowned Light", kind: "Storyline", summary: "Wren follows the Tidewitch's cold light into the drowned town to bring it back, and learns why it sank.",
          theme: "Letting go", acts: [
            { title: "The night the sea rose", text: "The old keeper vanishes; Wren finds the Everlantern still burning." },
            { title: "Raising the town", text: "District by district, Wren lifts the streets and meets the people who stayed." },
            { title: "The cold lantern", text: "Wren reaches the Tidewitch and chooses between fighting her and sharing the flame." },
          ], twists: ["The Tidewitch is the town's first lighthouse keeper."], cast: [{ id: wren }, { id: witch }, { id: moss }], art: [] },
        { id: id(), created: now, name: "Low Tide", kind: "Chapter", order: 1, summary: "Wren learns to lift things with light and reaches the docks.",
          beats: [{ title: "Wake up", text: "Storm outside, the lighthouse is dark." }, { title: "First light", text: "Lift a sunken rowboat to cross the bay." }],
          where: [{ id: docks }], cast: [{ id: wren }, { id: moss }], art: [] },
        { id: id(), created: now, name: "Bells Under Water", kind: "Quest", questType: "Side", summary: "The harbor master wants the three dock bells raised so ships can find their way home.",
          giver: [{ id: moss }], objectives: [{ text: "Raise the west bell", done: true }, { text: "Raise the east bell", done: false }, { text: "Ring all three at dusk", done: false }],
          rewards: [{ id: compass }], rewardText: "Opens the road to the Sunken Library", where: [{ id: docks }], art: [] },
        { id: id(), created: now, name: "Mossback wakes up", kind: "Dialogue", summary: "First meeting on the docks.", cast: [{ id: wren }, { id: moss }], where: [{ id: docks }],
          dialogue: { nodes: [
            { id: n1, speaker: moss, text: "Mm? A flame... a real one. I thought they'd all gone out.", choices: [] },
            { id: n2, speaker: "player", text: "Who are you?", choices: [{ text: "Can you help me cross?", to: n3 }, { text: "Do you know where the keeper went?", to: n4 }] },
            { id: n3, speaker: moss, text: "Climb on. Hold the light high. Slow is still moving.", choices: [], next: "end" },
            { id: n4, speaker: moss, text: "Down, down, where the cold lantern glows. Don't go yet. You'll need more than one flame.", choices: [], next: n5 },
            { id: n5, speaker: "player", text: "Then teach me.", choices: [], next: "end" },
          ] }, art: [] },
      ],
      mechanics: [
        { id: light, created: now, name: "Lantern light", category: "Core loop", status: "Prototyping",
          description: "Hold the lantern toward a drowned object to lift it out of the water. It sinks again when the light leaves, unless it's anchored.",
          rules: ["Bigger objects need more time in the light.", "Only one object can be held up at a time.", "Anchors keep lifted things up for good."],
          controls: [["Right mouse / RT", "Raise lantern"], ["Scroll / bumpers", "Widen or focus the beam"]],
          tuning: [["Lift speed", "1.5 m/s"], ["Beam range", "8 m"], ["Sink delay", "2 s"]],
          fun: "Lifting a whole street out of the sea feels huge and calm at the same time.", risks: ["Does holding a button get tiring?"], uses: [{ id: wren }], art: [] },
        { id: pull, created: now, name: "Tide pull", category: "Movement", status: "Idea", description: "Tug the tide toward you to slide across gaps on the water's surface.",
          rules: ["Only works when the tide is going out."], controls: [["Shift / B", "Tide pull"]], art: [] },
      ],
      media: [
        { id: id(), created: now, name: "First teaser: The Last Flame", type: "Teaser", status: "Storyboard", length: "0:30", posted: ["YouTube", "Steam page"],
          summary: "Dark, rainy harbor. One warm light appears and the drowned town rises around it.", store: "Yes",
          script: [
            { title: "Black screen, rain", text: "Only the sound of rain and a distant bell." },
            { title: "The lantern lights", text: "Wren's face, lit from below. The title card fades in." },
            { title: "The town rises", text: "A whole street lifts out of the sea in one long shot." },
          ],
          shots: [{ text: "Rain ambience recorded", done: true }, { text: "Street-rising shot", done: false }, { text: "Title card", done: false }],
          music: "Solo accordion, then strings", features: [{ id: wren }], levels: [{ id: docks }], art: [] },
      ],
      levels: [
        { id: docks, created: now, name: "Driftwood Docks", order: 1, biome: "Flooded harbor at dusk", objective: "Raise the three dock bells and cross the bay.",
          difficulty: 2, mood: ["Cozy", "Mysterious"], layout: "Start at the lighthouse stairs. Three piers fan out; each bell is under a different pier. The rowboat puzzle teaches lifting.",
          enemies: [{ id: moss }], items: [{ id: lantern }], mechanics: [{ id: light }], next: [{ id: library }], music: "Slow accordion, rain on wood", art: [] },
        { id: library, created: now, name: "Sunken Library", order: 2, biome: "A drowned library full of floating books", objective: "Find the Tide Compass and the map to the old town.",
          difficulty: 5, mood: ["Eerie", "Peaceful"], layout: "Shelves become platforms when lifted. Books float up as stepping stones. The compass sits in the reading room dome.",
          items: [{ id: compass }], mechanics: [{ id: light }, { id: pull }], music: "Music box under water", art: [] },
      ],
    },
  });
};
