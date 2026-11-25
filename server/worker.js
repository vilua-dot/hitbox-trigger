// Cloudflare Worker — serves injector.dll, loader.dll, agent.jar from R2 bucket
// Deploy: wrangler deploy
// Upload files: wrangler r2 object put trigger-files/injector.dll --file dist/injector.dll
//               wrangler r2 object put trigger-files/loader.dll --file dist/loader.dll
//               wrangler r2 object put trigger-files/agent.jar --file dist/agent.jar

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.slice(1);

    const allowed = { "injector.dll": 1, "loader.dll": 1, "agent.jar": 1 };
    if (!allowed[path]) return new Response("", { status: 404 });

    const obj = await env.FILES.get(path);
    if (!obj) return new Response("", { status: 404 });

    return new Response(obj.body, {
      headers: { "Content-Type": "application/octet-stream" },
    });
  },
};
