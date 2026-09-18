# Agent Input Panel Component

Standalone prototype and replica of the Google Antigravity `AgentInputBox` chat input component.

## Contents
- `AgentInputBox.tsx`: React/TypeScript implementation of the prompt and command input bar styled with Tailwind CSS.
- `index.html`: Standalone live preview page demonstrating the input box behavior, responsiveness, and dark styling.
- `extracted/`: Raw reverse-engineered JavaScript chunks extracted from the official core bundle (`web_ui/main.js`):
  - `agent_input_box_raw.js`: Raw component definitions.
  - `extracted_chat_bar.js`: Chat bar container and toolbar actions.
  - `extracted_prompt_panel.js`: Prompt panel and input state handling.
