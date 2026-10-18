/* Build settings.
   githubClientId: the Client ID of the "Game Codex" OAuth App on GitHub (Settings → Developer settings →
   OAuth Apps, with "Enable Device Flow" ticked). It isn't secret. Without it, sign-in uses a personal
   access token instead of the one-click code. */
window.Codex = window.Codex || {};
Codex.CONFIG = {
  githubClientId: "",
  savesRepo: "game-codex-saves",
};
