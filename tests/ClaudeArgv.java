import io.agentfarm.core.ClaudeCodeAdapter;
import io.agentfarm.core.Json;
import java.util.List;

/** Prints the exact command the executor runs for a Claude Code turn. */
public class ClaudeArgv {
  public static void main(String[] args) {
    System.out.println(
        Json.encode(
            ClaudeCodeAdapter.command(
                "claude", args[0], args[1], List.of(), null, List.of(args[2]))));
  }
}
