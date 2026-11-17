import Foundation

/// Resumes a continuation once, whoever gets there first.
nonisolated final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func claim() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

nonisolated enum Async {
    /// The task's value if it finishes within `seconds`, else nil. The task itself is NOT cancelled (Android's
    /// `withTimeoutOrNull { deferred.await() }` on a separate scope: e.g. the end message must still reach the server).
    static func value<T: Sendable>(of task: Task<T?, Never>, within seconds: Double) async -> T? {
        await withCheckedContinuation { (cont: CheckedContinuation<T?, Never>) in
            let once = Once()
            Task {
                let v = await task.value
                if once.claim() { cont.resume(returning: v) }
            }
            Task {
                try? await Task.sleep(nanoseconds: UInt64(max(seconds, 0) * 1_000_000_000))
                if once.claim() { cont.resume(returning: nil) }
            }
        }
    }

    /// Waits until every task is done or `seconds` pass, whichever is first. Returns true if all finished.
    @discardableResult
    static func waitAll(_ tasks: [Task<Void, Never>], within seconds: Double) async -> Bool {
        let all = Task<Bool?, Never> {
            for t in tasks { await t.value }
            return true
        }
        return await value(of: all, within: seconds) ?? false
    }

    /// Sleeps; returns false if the task was cancelled meanwhile.
    static func sleep(ms: Int) async -> Bool {
        if ms > 0 { try? await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000) }
        return !Task.isCancelled
    }
}
