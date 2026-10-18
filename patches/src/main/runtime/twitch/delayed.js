// Compiler input only: the patch transplants this one callback into the pinned main bundle.
// The original caller creates its parent environment with the moderation event in slot zero.
globalThis[1998063108] = function (event) {
    return function lurkDelayedPredicate(line) {
        try {
            var hook = globalThis[1998063107];
            if (typeof hook === 'function' && hook[1998063109] === 1998063110) {
                var result = hook(line, event);
                if (typeof result === 'boolean') return result;
            }
        } catch (ignored) {
            // Includes a throwing global getter, helper, and invalid/unsupported bootstrap paths.
        }
        // Exactly the stock predicate. Do not replace failures with either unconditional boolean.
        if (line.type !== 'chat' && line.type !== 'firstchatter' && line.type !== 'channelpointsreward') return false;
        return event.kind === 'delete' ? line.id === event.targetMsgId : line.user.toLowerCase() === event.userLogin;
    };
};
