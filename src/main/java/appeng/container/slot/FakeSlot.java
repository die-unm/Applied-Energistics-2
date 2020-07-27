/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.container.slot;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

import alexiil.mc.lib.attributes.item.FixedItemInv;

public class FakeSlot extends AppEngSlot {

    public FakeSlot(final FixedItemInv inv, final int idx, final int x, final int y) {
        super(inv, idx, x, y);
    }

    @Override
    public ItemStack onTakeItem(final PlayerEntity par1PlayerEntity, final ItemStack par2ItemStack) {
        return par2ItemStack;
    }

    @Override
    public ItemStack takeStack(final int par1) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canInsert(final ItemStack par1ItemStack) {
        return false;
    }

    @Override
    public void setStack(ItemStack is) {
        if (!is.isEmpty()) {
            is = is.copy();
        }

        super.setStack(is);
    }

    @Override
    public boolean canTakeItems(final PlayerEntity par1PlayerEntity) {
        return false;
    }
}
