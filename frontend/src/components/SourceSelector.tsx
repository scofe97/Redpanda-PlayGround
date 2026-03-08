import { useState } from 'react';
import type { TicketSource } from '../api/ticketApi';
import { useGitRepos, useGitBranches, useNexusArtifacts, useRegistryImages, useRegistryTags } from '../hooks/useSources';

const SOURCE_TYPES = ['GIT', 'NEXUS', 'HARBOR'] as const;

const sourceIcons: Record<string, string> = {
  GIT: 'code',
  NEXUS: 'package_2',
  HARBOR: 'dock',
};

interface SourceSelectorProps {
  sources: TicketSource[];
  onChange: (sources: TicketSource[]) => void;
}

export default function SourceSelector({ sources, onChange }: SourceSelectorProps) {
  const toggleSource = (type: string) => {
    if (sources.some((s) => s.sourceType === type)) {
      onChange(sources.filter((s) => s.sourceType !== type));
    } else {
      onChange([...sources, { sourceType: type }]);
    }
  };

  const updateSource = (type: string, fields: Partial<TicketSource>) => {
    onChange(sources.map((s) => (s.sourceType === type ? { ...s, ...fields } : s)));
  };

  return (
    <div>
      <div className="flex flex-wrap gap-4 mb-6">
        {SOURCE_TYPES.map((type) => (
          <label
            key={type}
            className="flex items-center gap-2 cursor-pointer bg-white dark:bg-slate-800 px-4 py-2 rounded-lg border border-slate-200 dark:border-slate-700"
          >
            <input
              type="checkbox"
              checked={sources.some((s) => s.sourceType === type)}
              onChange={() => toggleSource(type)}
              className="rounded text-primary focus:ring-primary"
            />
            <span className="text-sm font-medium">{type}</span>
          </label>
        ))}
      </div>

      <div className="space-y-4">
        {sources.map((source) => (
          <div
            key={source.sourceType}
            className="bg-white dark:bg-slate-800 p-6 rounded-lg border-l-4 border-l-primary shadow-sm space-y-4"
          >
            <div className="flex items-center gap-2 text-primary mb-2">
              <span className="material-symbols-outlined text-lg">{sourceIcons[source.sourceType]}</span>
              <span className="text-sm font-bold uppercase tracking-wider">{source.sourceType} 설정</span>
            </div>

            {source.sourceType === 'GIT' && (
              <GitSourceFields source={source} onUpdate={(fields) => updateSource('GIT', fields)} />
            )}

            {source.sourceType === 'NEXUS' && (
              <NexusSourceFields source={source} onUpdate={(fields) => updateSource('NEXUS', fields)} />
            )}

            {source.sourceType === 'HARBOR' && (
              <HarborSourceFields source={source} onUpdate={(fields) => updateSource('HARBOR', fields)} />
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

const inputClass =
  'w-full text-sm rounded-lg border-slate-300 dark:border-slate-700 dark:bg-slate-900 focus:ring-primary focus:border-primary';
const labelClass = 'block text-xs font-bold text-slate-500 dark:text-slate-400 mb-1 uppercase';

function GitSourceFields({
  source,
  onUpdate,
}: {
  source: TicketSource;
  onUpdate: (fields: Partial<TicketSource>) => void;
}) {
  const { data: repos, isLoading: reposLoading } = useGitRepos();
  const [selectedProjectId, setSelectedProjectId] = useState<number>(0);
  const { data: branches, isLoading: branchesLoading } = useGitBranches(selectedProjectId);

  const handleProjectSelect = (projectId: number) => {
    setSelectedProjectId(projectId);
    const project = repos?.find((r) => r.id === projectId);
    if (project) {
      onUpdate({ repoUrl: project.web_url, branch: '' });
    }
  };

  const handleBranchSelect = (branchName: string) => {
    onUpdate({ branch: branchName });
  };

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
      <label className="block">
        <span className={labelClass}>Repository</span>
        <select
          value={selectedProjectId}
          onChange={(e) => handleProjectSelect(Number(e.target.value))}
          className={inputClass}
        >
          <option value={0}>{reposLoading ? '로딩 중...' : '프로젝트 선택'}</option>
          {repos?.map((r) => (
            <option key={r.id} value={r.id}>
              {r.name_with_namespace}
            </option>
          ))}
        </select>
        {source.repoUrl && (
          <span className="block text-xs text-slate-400 mt-1 truncate">{source.repoUrl}</span>
        )}
      </label>
      <label className="block">
        <span className={labelClass}>Branch</span>
        <select
          value={source.branch || ''}
          onChange={(e) => handleBranchSelect(e.target.value)}
          disabled={selectedProjectId === 0}
          className={`${inputClass} disabled:opacity-50`}
        >
          <option value="">{branchesLoading ? '로딩 중...' : '브랜치 선택'}</option>
          {branches?.map((b) => (
            <option key={b.name} value={b.name}>
              {b.name}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}

function NexusSourceFields({
  source,
  onUpdate,
}: {
  source: TicketSource;
  onUpdate: (fields: Partial<TicketSource>) => void;
}) {
  const [groupId, setGroupId] = useState('');
  const [artifactId, setArtifactId] = useState('');
  const [searchEnabled, setSearchEnabled] = useState(false);
  const { data: artifacts, isLoading } = useNexusArtifacts(groupId, artifactId, searchEnabled);

  const handleSearch = () => {
    if (groupId && artifactId) {
      setSearchEnabled(true);
    }
  };

  const handleSelect = (value: string) => {
    onUpdate({ artifactCoordinate: value });
  };

  const artifactOptions = artifacts?.map((a) => {
    const m = a.maven2;
    return m ? `${m.groupId}:${m.artifactId}:${m.version}:${m.extension}` : '';
  }).filter(Boolean) ?? [];

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 items-end">
        <label className="block">
          <span className={labelClass}>Group ID</span>
          <input
            value={groupId}
            onChange={(e) => { setGroupId(e.target.value); setSearchEnabled(false); }}
            placeholder="com.example"
            className={inputClass}
          />
        </label>
        <label className="block">
          <span className={labelClass}>Artifact ID</span>
          <input
            value={artifactId}
            onChange={(e) => { setArtifactId(e.target.value); setSearchEnabled(false); }}
            placeholder="my-app"
            className={inputClass}
          />
        </label>
        <button
          type="button"
          onClick={handleSearch}
          disabled={!groupId || !artifactId || isLoading}
          className="px-4 py-2 bg-slate-700 text-white text-sm font-bold rounded-lg hover:bg-slate-600 disabled:opacity-50 transition-colors"
        >
          {isLoading ? '검색 중...' : '검색'}
        </button>
      </div>

      <label className="block">
        <span className={labelClass}>Artifact</span>
        <select
          value={source.artifactCoordinate || ''}
          onChange={(e) => handleSelect(e.target.value)}
          disabled={artifactOptions.length === 0}
          className={`${inputClass} disabled:opacity-50`}
        >
          <option value="">
            {isLoading ? '검색 중...' : artifactOptions.length === 0 ? '검색 결과 없음' : '아티팩트 선택'}
          </option>
          {artifactOptions.map((coord) => (
            <option key={coord} value={coord}>
              {coord}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}

function HarborSourceFields({
  source,
  onUpdate,
}: {
  source: TicketSource;
  onUpdate: (fields: Partial<TicketSource>) => void;
}) {
  const { data: images, isLoading: imagesLoading } = useRegistryImages();
  const [selectedRepo, setSelectedRepo] = useState('');
  const { data: tags, isLoading: tagsLoading } = useRegistryTags(selectedRepo);

  const handleRepoSelect = (repo: string) => {
    setSelectedRepo(repo);
    onUpdate({ imageName: '' });
  };

  const handleTagSelect = (tag: string) => {
    onUpdate({ imageName: `${selectedRepo}:${tag}` });
  };

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
      <label className="block">
        <span className={labelClass}>Image</span>
        <select
          value={selectedRepo}
          onChange={(e) => handleRepoSelect(e.target.value)}
          className={inputClass}
        >
          <option value="">{imagesLoading ? '로딩 중...' : '이미지 선택'}</option>
          {images?.map((img) => (
            <option key={img} value={img}>
              {img}
            </option>
          ))}
        </select>
      </label>
      <label className="block">
        <span className={labelClass}>Tag</span>
        <select
          value={source.imageName?.split(':')[1] || ''}
          onChange={(e) => handleTagSelect(e.target.value)}
          disabled={!selectedRepo}
          className={`${inputClass} disabled:opacity-50`}
        >
          <option value="">{tagsLoading ? '로딩 중...' : '태그 선택'}</option>
          {tags?.map((tag) => (
            <option key={tag} value={tag}>
              {tag}
            </option>
          ))}
        </select>
      </label>
      {source.imageName && (
        <div className="md:col-span-2 flex items-center gap-2 bg-slate-50 dark:bg-slate-800/50 px-3 py-2 rounded-lg">
          <span className="material-symbols-outlined text-sm text-primary">check_circle</span>
          <span className="text-sm font-medium">{source.imageName}</span>
        </div>
      )}
    </div>
  );
}
