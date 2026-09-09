package com.smartoffice.breakfast.repository;

import com.smartoffice.breakfast.entity.Role;
import com.smartoffice.breakfast.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhone(String phone);
    boolean existsByPhone(String phone);
    List<User> findAllByOrderByNameAsc();

    /**
     * Used by {@code UserAdminService#demoteToUser} to refuse a demotion that
     * would leave the system with zero admins. Counted fresh from the
     * database (not off a cached list) so it stays correct even under
     * concurrent admin actions.
     */
    long countByRole(Role role);

    /**
     * Bulk-promotes every non-admin user to ADMIN in a single UPDATE
     * statement (no N+1 load-modify-save round trips). The acting admin's
     * own id is always excluded at the query level — not just in the
     * service — so this can never be used to touch the caller's own row,
     * even if the service-layer check is ever bypassed or refactored away.
     *
     * @return the number of rows actually flipped to ADMIN
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.role = com.smartoffice.breakfast.entity.Role.ADMIN "
            + "WHERE u.role <> com.smartoffice.breakfast.entity.Role.ADMIN AND u.id <> :excludedUserId")
    int promoteAllUsersToAdmin(@Param("excludedUserId") Long excludedUserId);
}