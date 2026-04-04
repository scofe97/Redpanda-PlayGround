import { useState, useRef, useCallback } from 'react';
import toast from 'react-hot-toast';
import { useNexusUpload } from '../hooks/useNexusUpload';
import { usePurposeList } from '../hooks/usePipelineDefinition';
import { Purpose } from '../api/purposeApi';

function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

const INPUT_CLASS =
  'w-full px-3 py-2 rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm focus:ring-2 focus:ring-primary focus:border-transparent outline-none';
const LABEL_CLASS = 'text-sm font-medium text-slate-700 dark:text-slate-300';

export default function ArtifactUploadPage() {
  const { data: purposes, isLoading: purposesLoading } = usePurposeList();
  const { mutateAsync, isPending, isSuccess, isError, error, data, progress, reset } =
    useNexusUpload();

  const [purposeId, setPurposeId] = useState<number | ''>('');
  const [repository, setRepository] = useState('maven-releases');
  const [groupId, setGroupId] = useState('');
  const [artifactId, setArtifactId] = useState('');
  const [version, setVersion] = useState('');
  const [packaging, setPackaging] = useState('jar');
  const [file, setFile] = useState<File | null>(null);
  const [isDragging, setIsDragging] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);

  const libraryPurposes: Purpose[] = (purposes ?? []).filter((p) =>
    p.entries.some((e) => e.category === 'LIBRARY')
  );

  const selectedPurpose = libraryPurposes.find((p) => p.id === purposeId) ?? null;
  const libraryEntry = selectedPurpose?.entries.find((e) => e.category === 'LIBRARY') ?? null;

  const isFormValid =
    purposeId !== '' &&
    groupId.trim() !== '' &&
    artifactId.trim() !== '' &&
    version.trim() !== '' &&
    file !== null;

  const applyFile = (f: File) => setFile(f);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  }, []);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
  }, []);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    const dropped = e.dataTransfer.files[0];
    if (dropped) applyFile(dropped);
  }, []);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const picked = e.target.files?.[0];
    if (picked) applyFile(picked);
  };

  const handleSubmit = async () => {
    if (!isFormValid || !file) return;
    reset();
    try {
      await mutateAsync({ repository, groupId, artifactId, version, packaging, file });
      toast.success('아티팩트 업로드 완료');
    } catch {
      toast.error('업로드 실패');
    }
  };

  return (
    <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
      {/* Header */}
      <header className="h-16 border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-8 flex items-center gap-2">
        <span className="material-symbols-outlined text-primary">upload_file</span>
        <h2 className="text-lg font-bold">아티팩트 업로드</h2>
      </header>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-8">
        <div className="max-w-2xl mx-auto space-y-6">
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm p-6 space-y-6">

            {/* 목적 선택 */}
            <div className="space-y-2">
              <label className={LABEL_CLASS}>목적 선택</label>
              <select
                className={INPUT_CLASS}
                value={purposeId}
                onChange={(e) => setPurposeId(e.target.value === '' ? '' : Number(e.target.value))}
                disabled={purposesLoading}
              >
                <option value="">-- 목적을 선택하세요 --</option>
                {libraryPurposes.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
              {libraryEntry && (
                <div className="mt-2 px-4 py-3 rounded-lg bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700 space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="material-symbols-outlined text-sm text-slate-400">inventory_2</span>
                    <span className="text-sm font-medium text-slate-700 dark:text-slate-300">
                      {libraryEntry.toolName}
                    </span>
                  </div>
                  <a
                    href={libraryEntry.toolUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-xs text-primary hover:underline break-all"
                  >
                    {libraryEntry.toolUrl}
                  </a>
                </div>
              )}
            </div>

            {/* Divider */}
            <div className="border-t border-slate-100 dark:border-slate-800" />

            {/* Maven 좌표 */}
            <div className="space-y-4">
              <p className="text-sm font-semibold text-slate-800 dark:text-slate-200">Maven 좌표</p>

              <div className="space-y-2">
                <label className={LABEL_CLASS}>Repository</label>
                <select
                  className={INPUT_CLASS}
                  value={repository}
                  onChange={(e) => setRepository(e.target.value)}
                >
                  <option value="maven-releases">maven-releases</option>
                  <option value="maven-snapshots">maven-snapshots</option>
                </select>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <label className={LABEL_CLASS}>groupId</label>
                  <input
                    type="text"
                    className={INPUT_CLASS}
                    placeholder="com.example"
                    value={groupId}
                    onChange={(e) => setGroupId(e.target.value)}
                  />
                </div>
                <div className="space-y-2">
                  <label className={LABEL_CLASS}>artifactId</label>
                  <input
                    type="text"
                    className={INPUT_CLASS}
                    placeholder="my-app"
                    value={artifactId}
                    onChange={(e) => setArtifactId(e.target.value)}
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <label className={LABEL_CLASS}>version</label>
                  <input
                    type="text"
                    className={INPUT_CLASS}
                    placeholder="1.0.0"
                    value={version}
                    onChange={(e) => setVersion(e.target.value)}
                  />
                </div>
                <div className="space-y-2">
                  <label className={LABEL_CLASS}>packaging</label>
                  <select
                    className={INPUT_CLASS}
                    value={packaging}
                    onChange={(e) => setPackaging(e.target.value)}
                  >
                    <option value="jar">jar</option>
                    <option value="war">war</option>
                    <option value="ear">ear</option>
                    <option value="pom">pom</option>
                  </select>
                </div>
              </div>
            </div>

            {/* Divider */}
            <div className="border-t border-slate-100 dark:border-slate-800" />

            {/* 파일 선택 */}
            <div className="space-y-2">
              <label className={LABEL_CLASS}>파일 선택</label>
              <div
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
                onClick={() => fileInputRef.current?.click()}
                className={`relative flex flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed px-6 py-10 cursor-pointer transition-colors ${
                  isDragging
                    ? 'border-primary bg-primary/5'
                    : 'border-slate-300 dark:border-slate-700 hover:border-primary hover:bg-primary/5'
                }`}
              >
                <input
                  ref={fileInputRef}
                  type="file"
                  className="hidden"
                  accept=".jar,.war,.ear,.pom,.zip"
                  onChange={handleFileChange}
                />
                {file ? (
                  <>
                    <span className="material-symbols-outlined text-3xl text-primary">description</span>
                    <p className="text-sm font-semibold text-slate-700 dark:text-slate-300">{file.name}</p>
                    <p className="text-xs text-slate-400">{formatFileSize(file.size)}</p>
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        setFile(null);
                        if (fileInputRef.current) fileInputRef.current.value = '';
                      }}
                      className="absolute top-3 right-3 text-slate-400 hover:text-red-500 transition-colors"
                    >
                      <span className="material-symbols-outlined text-sm">close</span>
                    </button>
                  </>
                ) : (
                  <>
                    <span className="material-symbols-outlined text-3xl text-slate-400">cloud_upload</span>
                    <p className="text-sm text-slate-500 dark:text-slate-400">
                      파일을 드래그하거나 클릭하여 선택
                    </p>
                    <p className="text-xs text-slate-400">.jar, .war, .ear, .pom, .zip</p>
                  </>
                )}
              </div>
            </div>

            {/* 프로그레스 바 */}
            {isPending && (
              <div className="space-y-1.5">
                <div className="flex justify-between items-center">
                  <span className="text-xs text-slate-500">업로드 중...</span>
                  <span className="text-xs font-semibold text-primary">{progress}%</span>
                </div>
                <div className="w-full h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                  <div
                    className="h-full bg-primary rounded-full transition-all duration-200"
                    style={{ width: `${progress}%` }}
                  />
                </div>
              </div>
            )}

            {/* 업로드 버튼 */}
            <button
              type="button"
              onClick={handleSubmit}
              disabled={!isFormValid || isPending}
              className="w-full bg-primary hover:bg-primary/90 disabled:opacity-40 disabled:cursor-not-allowed text-white px-4 py-2.5 rounded-lg text-sm font-bold flex items-center justify-center gap-2 transition-all"
            >
              {isPending ? (
                <>
                  <svg className="animate-spin size-4" viewBox="0 0 24 24" fill="none">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
                  </svg>
                  업로드 중...
                </>
              ) : (
                <>
                  <span className="material-symbols-outlined text-sm">upload</span>
                  업로드
                </>
              )}
            </button>

            {/* 결과 표시 */}
            {isSuccess && data && (
              <div className="flex items-start gap-3 px-4 py-3 rounded-lg bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-800">
                <span className="material-symbols-outlined text-emerald-500 mt-0.5">check_circle</span>
                <div className="flex-1 min-w-0 space-y-1">
                  <p className="text-sm font-semibold text-emerald-700 dark:text-emerald-400">업로드 완료</p>
                  <a
                    href={data.deployedUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-xs text-emerald-600 dark:text-emerald-400 hover:underline break-all"
                  >
                    {data.deployedUrl}
                  </a>
                </div>
              </div>
            )}

            {isError && error && (
              <div className="flex items-start gap-3 px-4 py-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800">
                <span className="material-symbols-outlined text-red-500 mt-0.5">error</span>
                <div className="flex-1 min-w-0 space-y-1">
                  <p className="text-sm font-semibold text-red-700 dark:text-red-400">업로드 실패</p>
                  <p className="text-xs text-red-600 dark:text-red-400 break-all">
                    {error instanceof Error ? error.message : String(error)}
                  </p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
