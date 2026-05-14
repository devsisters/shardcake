package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.MessageCodec

/**
 * Kryo backend given. Import with `import com.devsisters.shardcake.kryo.given` to enable
 * Kryo-backed serialisation for all `EntityType` / `TopicType` declarations in the
 * surrounding scope.
 *
 * To use a custom Kryo configuration, shadow this given with your own:
 * {{{
 *   given [Msg]: MessageCodec[Msg] = KryoMessageCodec.fromConfig[Msg](myConfig)
 * }}}
 */
package object kryo {
  given derive[Msg]: MessageCodec[Msg] = KryoMessageCodec.default[Msg]
}
