/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql

import javax.inject.Inject
import org.maproulette.framework.graphql.schemas._
import sangria.schema.{ObjectType, fields}

/**
  * @author mcuthbert
  */
class GraphQL @Inject() (
    projectSchema: ProjectSchema,
    challengeSchema: ChallengeSchema,
    commentSchema: CommentSchema,
    grantSchema: GrantSchema,
    userSchema: UserSchema,
    tagSchema: TagSchema
) {
  private val queries =
    MRSchema.baseQueries ++
      projectSchema.queries ++
      challengeSchema.queries ++
      commentSchema.queries ++
      grantSchema.queries ++
      userSchema.queries ++
      tagSchema.queries

  private val mutations =
    MRSchema.baseMutations ++
      projectSchema.mutations ++
      challengeSchema.mutations ++
      commentSchema.mutations ++
      grantSchema.mutations ++
      userSchema.mutations ++
      tagSchema.mutations

  val schema: sangria.schema.Schema[UserContext, Unit] = sangria.schema.Schema[UserContext, Unit](
    query = ObjectType("Query", fields(queries: _*)),
    mutation = Some(ObjectType("Mutation", fields(mutations: _*)))
  )
}
