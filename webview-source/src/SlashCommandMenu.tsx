/**
 * SlashCommandMenu.tsx — filterable dropdown for slash commands.
 *
 * Appears when the user types "/" at the start of the input.
 * Supports full keyboard navigation: Arrow keys move selection, Enter
 * confirms, Escape dismisses, Tab confirms (same as Enter).
 */

import React, { useEffect, useRef } from "react";
import type { SlashCommand } from "./types";

interface Props {
  commands: SlashCommand[];
  filter: string;
  selectedIndex: number;
  onSelect: (command: SlashCommand) => void;
  onDismiss: () => void;
  onIndexChange: (index: number) => void;
}

const SlashCommandMenu: React.FC<Props> = ({
  commands,
  filter,
  selectedIndex,
  onSelect,
  onDismiss: _onDismiss,
  onIndexChange,
}) => {
  const listRef = useRef<HTMLUListElement>(null);

  const filtered = commands.filter((cmd) =>
    cmd.name.toLowerCase().startsWith(filter.toLowerCase())
  );

  // Scroll the highlighted item into view.
  useEffect(() => {
    const el = listRef.current?.children[selectedIndex] as HTMLElement | undefined;
    el?.scrollIntoView({ block: "nearest" });
  }, [selectedIndex]);

  if (filtered.length === 0) return null;

  return (
    <div className="rubyn-slash-menu" role="listbox" aria-label="Slash commands">
      <ul ref={listRef} className="rubyn-slash-menu__list">
        {filtered.map((cmd, idx) => (
          <li
            key={cmd.name}
            className={`rubyn-slash-menu__item${idx === selectedIndex ? " rubyn-slash-menu__item--selected" : ""}`}
            role="option"
            aria-selected={idx === selectedIndex}
            onMouseEnter={() => onIndexChange(idx)}
            onMouseDown={(e) => {
              // Prevent input blur before we can fire onSelect.
              e.preventDefault();
              onSelect(cmd);
            }}
          >
            <span className="rubyn-slash-menu__name">/{cmd.name}</span>
            <span className="rubyn-slash-menu__desc">{cmd.description}</span>
          </li>
        ))}
      </ul>
    </div>
  );
};

/**
 * useSlashCommandKeyboard — hook that handles keyboard events for the
 * slash command menu from an input element's onKeyDown handler.
 *
 * Returns: { handleKeyDown }
 */
export function useSlashCommandKeyboard(opts: {
  visible: boolean;
  total: number;
  selectedIndex: number;
  onIndexChange: (i: number) => void;
  onConfirm: () => void;
  onDismiss: () => void;
}) {
  const { visible, total, selectedIndex, onIndexChange, onConfirm, onDismiss } = opts;

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (!visible) return;

    switch (e.key) {
      case "ArrowDown":
        e.preventDefault();
        onIndexChange((selectedIndex + 1) % total);
        break;
      case "ArrowUp":
        e.preventDefault();
        onIndexChange((selectedIndex - 1 + total) % total);
        break;
      case "Enter":
      case "Tab":
        if (total > 0) {
          e.preventDefault();
          onConfirm();
        }
        break;
      case "Escape":
        e.preventDefault();
        onDismiss();
        break;
    }
  };

  return { handleKeyDown };
}

export default SlashCommandMenu;
