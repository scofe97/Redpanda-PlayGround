import { useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';

interface UseSSEOptions {
  ticketId: number;
  enabled: boolean;
}

const MAX_RECONNECT_DELAY = 30_000;
const INITIAL_DELAY = 1_000;

export function useSSE({ ticketId, enabled }: UseSSEOptions) {
  const queryClient = useQueryClient();
  const esRef = useRef<EventSource | null>(null);
  const retryDelayRef = useRef(INITIAL_DELAY);

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;
    let reconnectTimer: ReturnType<typeof setTimeout>;

    function connect() {
      if (cancelled) return;

      const es = new EventSource(`/api/tickets/${ticketId}/pipeline/events`);
      esRef.current = es;

      es.addEventListener('status', () => {
        queryClient.invalidateQueries({ queryKey: ['pipeline', ticketId] });
      });

      es.addEventListener('completed', () => {
        queryClient.invalidateQueries({ queryKey: ['pipeline', ticketId] });
        queryClient.invalidateQueries({ queryKey: ['ticket', ticketId] });
        es.close();
      });

      es.onopen = () => {
        retryDelayRef.current = INITIAL_DELAY;
      };

      es.onerror = () => {
        es.close();
        if (cancelled) return;

        const delay = retryDelayRef.current;
        retryDelayRef.current = Math.min(delay * 2, MAX_RECONNECT_DELAY);
        reconnectTimer = setTimeout(connect, delay);
      };
    }

    connect();

    return () => {
      cancelled = true;
      clearTimeout(reconnectTimer);
      esRef.current?.close();
      esRef.current = null;
    };
  }, [ticketId, enabled, queryClient]);
}
