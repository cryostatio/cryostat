/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat;

import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.smallrye.graphql.api.AdaptToScalar;
import io.smallrye.graphql.api.Scalar;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.UuidGenerator;

@MappedSuperclass
public abstract class PanacheUuidEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    @GeneratedValue
    @UuidGenerator
    @NotNull
    @AdaptToScalar(Scalar.String.class)
    public UUID id;
}
