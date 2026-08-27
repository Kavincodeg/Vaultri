import { defineConfig } from 'prisma/config'

export default defineConfig({
  earlyAccess: true,
  // Schema is at the default location: prisma/schema.prisma
  // Seed: run `npx prisma db seed` which calls `npm run prisma:seed`
})
