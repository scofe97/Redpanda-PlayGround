import { useState, useEffect, useCallback } from 'react';

interface ConfigJsonEditorProps {
  value: string;
  onChange: (json: string) => void;
  defaults?: { key: string; placeholder: string }[];
}

interface Entry {
  key: string;
  value: string;
  placeholder?: string;
}

function parseEntries(json: string, defaults?: { key: string; placeholder: string }[]): Entry[] {
  if (json.trim()) {
    try {
      const obj = JSON.parse(json);
      if (typeof obj === 'object' && obj !== null && !Array.isArray(obj)) {
        const entries: Entry[] = Object.entries(obj).map(([k, v]) => {
          const def = defaults?.find((d) => d.key === k);
          return { key: k, value: String(v ?? ''), placeholder: def?.placeholder };
        });
        if (entries.length > 0) return entries;
      }
    } catch {
      // fall through to defaults
    }
  }
  if (defaults && defaults.length > 0) {
    return defaults.map((d) => ({ key: d.key, value: '', placeholder: d.placeholder }));
  }
  return [];
}

function serializeEntries(entries: Entry[]): string {
  const filled = entries.filter((e) => e.key.trim());
  if (filled.length === 0) return '';
  const obj: Record<string, string> = {};
  for (const e of filled) {
    if (e.value.trim()) obj[e.key.trim()] = e.value;
  }
  return Object.keys(obj).length > 0 ? JSON.stringify(obj) : '';
}

export default function ConfigJsonEditor({ value, onChange, defaults }: ConfigJsonEditorProps) {
  const [entries, setEntries] = useState<Entry[]>(() => parseEntries(value, defaults));

  // Re-parse when value changes externally (e.g. on mount with fetched data)
  useEffect(() => {
    setEntries(parseEntries(value, defaults));
  }, [value, defaults]);

  const update = useCallback(
    (next: Entry[]) => {
      setEntries(next);
      onChange(serializeEntries(next));
    },
    [onChange],
  );

  const handleKeyChange = (idx: number, newKey: string) => {
    const next = entries.map((e, i) => (i === idx ? { ...e, key: newKey } : e));
    update(next);
  };

  const handleValueChange = (idx: number, newVal: string) => {
    const next = entries.map((e, i) => (i === idx ? { ...e, value: newVal } : e));
    update(next);
  };

  const handleRemove = (idx: number) => {
    const next = entries.filter((_, i) => i !== idx);
    update(next);
  };

  const handleAdd = () => {
    update([...entries, { key: '', value: '' }]);
  };

  return (
    <div className="space-y-2">
      {entries.map((entry, idx) => (
        <div key={idx} className="flex items-center gap-2">
          <input
            value={entry.key}
            onChange={(e) => handleKeyChange(idx, e.target.value)}
            placeholder="KEY"
            className="w-40 px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm font-mono focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
          />
          <span className="text-slate-400 text-xs font-mono">=</span>
          <input
            value={entry.value}
            onChange={(e) => handleValueChange(idx, e.target.value)}
            placeholder={entry.placeholder || 'value'}
            className="flex-1 px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
          />
          <button
            type="button"
            onClick={() => handleRemove(idx)}
            className="text-slate-400 hover:text-red-500 transition-colors p-1"
            title="삭제"
          >
            <span className="material-symbols-outlined text-[18px]">close</span>
          </button>
        </div>
      ))}
      <button
        type="button"
        onClick={handleAdd}
        className="text-sm text-primary hover:text-primary/80 transition-colors flex items-center gap-1 mt-1"
      >
        <span className="material-symbols-outlined text-[16px]">add</span>
        추가
      </button>
    </div>
  );
}
