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
        <div className="card" style={{ textAlign: 'center', padding: 32 }}>
          <h2>Something went wrong</h2>
          <p style={{ color: '#666', marginTop: 8 }}>
            {this.state.error?.message || 'An unexpected error occurred.'}
          </p>
          <button className="btn btn--primary" onClick={this.handleReset} style={{ marginTop: 16 }}>
            Try Again
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}
