import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import fp from 'fastify-plugin';
import type { User } from '@prisma/client';
import { getConfig } from '../config/env';
import { unauthorized } from '../lib/errors';
import { prisma } from '../lib/prisma';
import { getLocalUser } from '../services/localUser';

declare module 'fastify' {
  interface FastifyInstance {
    authenticate: (request: FastifyRequest, reply: FastifyReply) => Promise<void>;
  }
  interface FastifyRequest {
    currentUser: User;
  }
}

/**
 * JWT guard. Adds `request.currentUser`, so handlers always have timezone,
 * locale and unit preferences available without another lookup.
 */
export default fp(async function authPlugin(app: FastifyInstance) {
  // Declared up front so Fastify keeps a stable request shape.
  app.decorateRequest('currentUser', null as unknown as User);

  app.decorate('authenticate', async function authenticate(request: FastifyRequest) {
    // Private instance: there is exactly one account and no login at all.
    if (getConfig().SINGLE_USER_MODE) {
      request.currentUser = await getLocalUser();
      return;
    }

    try {
      await request.jwtVerify();
    } catch {
      throw unauthorized('Ungültiges oder abgelaufenes Token.');
    }

    const payload = request.user as { sub?: string } | undefined;
    const userId = payload?.sub;
    if (!userId) throw unauthorized();

    const user = await prisma().user.findUnique({ where: { id: userId } });
    if (!user) throw unauthorized('Benutzer existiert nicht mehr.');

    request.currentUser = user;
  });
});
