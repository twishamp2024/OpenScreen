export type ThemeChoice = "system" | "light" | "dark";

/** Applies the theme choice; "system" follows prefers-color-scheme via tokens.css. */
export function applyTheme(choice: ThemeChoice, root: HTMLElement = document.documentElement) {
  if (choice === "system") delete root.dataset.theme;
  else root.dataset.theme = choice;
}
