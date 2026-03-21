import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export default class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('ErrorBoundary caught:', error, errorInfo);
  }

  private handleReset = () => {
    this.setState({ hasError: false, error: null });
  };

  render() {
    if (this.state.hasError) {
      return (
        <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm p-8 max-w-lg mx-auto mt-20 text-center">
          <h2 className="text-lg font-bold">Something went wrong</h2>
          <p className="text-slate-500 dark:text-slate-400 mt-2 text-sm">
            {this.state.error?.message || 'An unexpected error occurred.'}
          </p>
          <button
            className="mt-6 px-6 py-2.5 text-sm font-bold text-white bg-primary hover:bg-primary/90 rounded-lg transition-colors"
            onClick={this.handleReset}
          >
            Try Again
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}
