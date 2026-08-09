import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { pullChanges, pushChanges, syncOperationSchema } from '../services/syncService';
import { parseBody } from './auth';

export default async function syncRoutes(app: FastifyInstance) {
  app.addHook('preHandler', app.authenticate);

  app.get('/', async (request) => {
    const query = request.query as { since?: string };
    const since = query.since ? new Date(query.since) : null;
    if (since && Number.isNaN(since.getTime())) {
      return pullChanges(request.currentUser.id, null);
    }
    return pullChanges(request.currentUser.id, since);
  });

  app.post('/', async (request) => {
    const body = parseBody(z.object({ operations: z.array(syncOperationSchema).max(500) }), request.body);
    const result = await pushChanges(request.currentUser.id, body.operations);
    return { ...result, serverTime: new Date().toISOString() };
  });
}
