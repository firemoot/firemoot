import net from "node:net";

/**
 * A minimal pass-through TCP proxy for chaos testing (PLAN.md M4.6). It forwards
 * raw bytes to a target (so it tunnels the WebSocket upgrade transparently) and
 * can sever every live connection on demand - a mid-stream network drop the
 * client must recover from. Hand-rolled on `node:net` (no toxiproxy dependency)
 * for full control of the timing.
 */
export class TcpProxy {
  private server: net.Server | null = null;
  private readonly sockets = new Set<net.Socket>();
  private accepting = true;

  constructor(
    private readonly targetHost: string,
    private readonly targetPort: number,
  ) {}

  /** Starts listening on `127.0.0.1` (random port unless given); returns the port. */
  async start(port = 0): Promise<number> {
    const server = net.createServer((client) => {
      if (!this.accepting) {
        client.destroy();
        return;
      }
      const upstream = net.connect(this.targetPort, this.targetHost);
      this.track(client);
      this.track(upstream);
      client.pipe(upstream);
      upstream.pipe(client);
      const cut = (): void => {
        client.destroy();
        upstream.destroy();
      };
      client.on("error", cut);
      upstream.on("error", cut);
      client.on("close", () => upstream.destroy());
      upstream.on("close", () => client.destroy());
    });
    this.server = server;
    await new Promise<void>((resolve) => server.listen(port, "127.0.0.1", resolve));
    return (server.address() as net.AddressInfo).port;
  }

  private track(socket: net.Socket): void {
    this.sockets.add(socket);
    socket.on("close", () => this.sockets.delete(socket));
  }

  /** Severs every live connection (the listener stays up, so reconnects succeed). */
  drop(): void {
    for (const socket of [...this.sockets]) socket.destroy();
  }

  /** When false, new connections are refused at once (a hard outage window). */
  setAccepting(accepting: boolean): void {
    this.accepting = accepting;
    if (!accepting) this.drop();
  }

  async stop(): Promise<void> {
    this.drop();
    const server = this.server;
    this.server = null;
    if (server) await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}
