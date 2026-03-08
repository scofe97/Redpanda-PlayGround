import { NavLink } from 'react-router-dom';

interface SidebarProps {
  currentPath: string;
}

const navItems = [
  { to: '/tickets', icon: 'confirmation_number', label: '티켓' },
  { to: '/tools', icon: 'construction', label: '도구' },
];

export default function Sidebar({ currentPath }: SidebarProps) {
  return (
    <aside className="w-64 flex-shrink-0 border-r border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 flex flex-col">
      <div className="p-6 flex items-center gap-3">
        <div className="w-10 h-10 rounded-lg bg-primary flex items-center justify-center text-white">
          <span className="material-symbols-outlined">rocket_launch</span>
        </div>
        <div>
          <h1 className="font-bold text-sm tracking-tight">Redpanda</h1>
          <p className="text-xs text-slate-500">Playground</p>
        </div>
      </div>

      <nav className="flex-1 px-4 space-y-1">
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={() => {
              const isActive = currentPath.startsWith(item.to);
              return `flex items-center gap-3 px-3 py-2 rounded-lg transition-colors ${
                isActive
                  ? 'bg-primary/10 text-primary'
                  : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800'
              }`;
            }}
          >
            <span className="material-symbols-outlined text-[22px]">{item.icon}</span>
            <span className={`text-sm ${currentPath.startsWith(item.to) ? 'font-semibold' : 'font-medium'}`}>
              {item.label}
            </span>
          </NavLink>
        ))}
      </nav>

      <div className="p-4 border-t border-slate-200 dark:border-slate-800">
        <div className="flex items-center gap-3 p-2 rounded-lg">
          <div className="w-8 h-8 rounded-full bg-slate-200 dark:bg-slate-700 flex items-center justify-center">
            <span className="material-symbols-outlined text-sm">person</span>
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-xs font-semibold truncate">Admin</p>
            <p className="text-[10px] text-slate-500 truncate">admin@redpanda.io</p>
          </div>
        </div>
      </div>
    </aside>
  );
}
