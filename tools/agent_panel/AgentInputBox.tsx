import React, { useState, useRef, useEffect } from 'react';

export interface AgentInputPanelProps {
  placeholder?: string;
  selectedModel?: string;
  reasoningEffort?: string;
  onSend?: (text: string) => void;
  onAddContext?: () => void;
  onModelSelect?: () => void;
  onVoiceRecord?: () => void;
}

/**
 * Точная реплика компонента AgentInputBox из ядра Google Antigravity.
 * Стилизован через Tailwind CSS с поддержкой оригинальной темы.
 */
export const AgentInputBox: React.FC<AgentInputPanelProps> = ({
  placeholder = 'Ask anything, @ to mention, / for actions',
  selectedModel = 'Gemini 3.8 Flash',
  reasoningEffort = 'Medium',
  onSend,
  onAddContext,
  onModelSelect,
  onVoiceRecord,
}) => {
  const [text, setText] = useState('');
  const [isRecording, setIsRecording] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // Автоматическая подгонка высоты textarea под контент
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 240)}px`;
    }
  }, [text]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleSend = () => {
    if (!text.trim()) return;
    onSend?.(text);
    setText('');
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
    }
  };

  const hasText = text.trim().length > 0;

  return (
    <div
      id="antigravity.agentSidePanelInputBox"
      className="relative flex flex-col w-full p-px rounded-2xl bg-[#2e2e2e] shadow-xl transition-all"
    >
      <div className="relative flex flex-col gap-0 p-1.5 rounded-[calc(1rem-1px)] bg-[#1e1e1e] text-white">
        {/* Поле ввода сообщения */}
        <div className="relative w-full">
          <textarea
            ref={textareaRef}
            rows={1}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={placeholder}
            className="w-full resize-none bg-transparent px-3 pt-2 pb-1 text-sm text-[#ececec] placeholder-[#8e8e93] outline-none leading-relaxed transition-all"
            style={{ maxHeight: '240px' }}
          />
        </div>

        {/* Нижняя панель действий (Toolbar) */}
        <div className="flex w-full items-center justify-between gap-1 px-1.5 py-1">
          {/* Левая группа: Add Context (+) и Выбор модели */}
          <div className="flex min-w-0 flex-1 items-center gap-1.5">
            {/* Кнопка Добавить контекст (+) */}
            <button
              type="button"
              onClick={onAddContext}
              aria-label="Add context"
              className="flex items-center justify-center w-7 h-7 p-1.5 rounded-full text-[#a1a1aa] hover:text-white hover:bg-[#2e2e2e] transition-colors cursor-pointer"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
              </svg>
            </button>

            {/* Выбор модели и уровня рассуждений */}
            <button
              type="button"
              onClick={onModelSelect}
              aria-label={`Select model, current: ${selectedModel} ${reasoningEffort}`}
              className="flex items-center h-7 gap-1 rounded-lg px-2 text-xs text-[#a1a1aa] hover:text-white hover:bg-[#2e2e2e] transition-colors cursor-pointer select-none"
            >
              <span className="truncate max-w-[160px] font-medium text-[#d4d4d8]">
                {selectedModel}
              </span>
              {reasoningEffort && (
                <span className="opacity-60 text-[11px]">{reasoningEffort}</span>
              )}
              <svg className="w-3.5 h-3.5 opacity-60 ml-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="m4.5 15.75 7.5-7.5 7.5 7.5" />
              </svg>
            </button>
          </div>

          {/* Правая группа: Микрофон и Кнопка Отправки */}
          <div className="shrink-0 flex items-center gap-1.5">
            {/* Голосовой ввод / Микрофон */}
            <button
              type="button"
              onClick={() => {
                setIsRecording(!isRecording);
                onVoiceRecord?.();
              }}
              aria-label="Voice Input"
              className={`flex items-center justify-center w-7 h-7 p-1 rounded-full transition-colors ${
                isRecording
                  ? 'bg-red-500/20 text-red-400'
                  : 'text-[#a1a1aa] hover:text-white hover:bg-[#2e2e2e]'
              }`}
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 18.75a6 6 0 0 0 6-6v-1.5m-6 7.5a6 6 0 0 1-6-6v-1.5m6 7.5v3.75m-3.75 0h7.5M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-3-3 3 3 0 0 0-3 3v6a3 3 0 0 0 3 3Z" />
              </svg>
            </button>

            {/* Кнопка отправки (Фиолетовая при наличии текста) */}
            <button
              type="button"
              disabled={!hasText}
              onClick={handleSend}
              aria-label="Send message"
              className={`flex items-center justify-center w-7 h-7 rounded-full transition-all duration-150 ${
                hasText
                  ? 'bg-[#d8b4fe] hover:bg-[#c084fc] text-[#18181b] shadow cursor-pointer'
                  : 'bg-[#27272a] text-[#52525b] cursor-not-allowed'
              }`}
            >
              <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.8}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M13.5 4.5 21 12m0 0-7.5 7.5M21 12H3" />
              </svg>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
