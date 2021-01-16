package com.geeksville.mesh.ui


import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.geeksville.android.Logging
import com.geeksville.mesh.NodeInfo
import com.geeksville.mesh.R
import com.geeksville.mesh.databinding.AdapterNodeLayoutBinding
import com.geeksville.mesh.databinding.NodelistFragmentBinding
import com.geeksville.mesh.model.UIViewModel
import java.text.ParseException
import java.util.*


class UsersFragment : ScreenFragment("Users"), Logging {

    private var _binding: NodelistFragmentBinding? = null
    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private val model: UIViewModel by activityViewModels()

    // Provide a direct reference to each of the views within a data item
    // Used to cache the views within the item layout for fast access
    class ViewHolder(itemView: AdapterNodeLayoutBinding) : RecyclerView.ViewHolder(itemView.root) {
        val nodeNameView = itemView.nodeNameView
        val distanceView = itemView.distanceView
        val batteryPctView = itemView.batteryPercentageView
        val lastTime = itemView.lastConnectionView
        val powerIcon = itemView.batteryIcon
    }

    private val nodesAdapter = object : RecyclerView.Adapter<ViewHolder>() {

        /**
         * Called when RecyclerView needs a new [ViewHolder] of the given type to represent
         * an item.
         *
         *
         * This new ViewHolder should be constructed with a new View that can represent the items
         * of the given type. You can either create a new View manually or inflate it from an XML
         * layout file.
         *
         *
         * The new ViewHolder will be used to display items of the adapter using
         * [.onBindViewHolder]. Since it will be re-used to display
         * different items in the data set, it is a good idea to cache references to sub views of
         * the View to avoid unnecessary [View.findViewById] calls.
         *
         * @param parent The ViewGroup into which the new View will be added after it is bound to
         * an adapter position.
         * @param viewType The view type of the new View.
         *
         * @return A new ViewHolder that holds a View of the given view type.
         * @see .getItemViewType
         * @see .onBindViewHolder
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(requireContext())

            // Inflate the custom layout
            val contactView = AdapterNodeLayoutBinding.inflate(inflater, parent, false)

            // Return a new holder instance
            return ViewHolder(contactView)
        }

        /**
         * Returns the total number of items in the data set held by the adapter.
         *
         * @return The total number of items in this adapter.
         */
        override fun getItemCount(): Int = nodes.size

        /**
         * Called by RecyclerView to display the data at the specified position. This method should
         * update the contents of the [ViewHolder.itemView] to reflect the item at the given
         * position.
         *
         *
         * Note that unlike [android.widget.ListView], RecyclerView will not call this method
         * again if the position of the item changes in the data set unless the item itself is
         * invalidated or the new position cannot be determined. For this reason, you should only
         * use the `position` parameter while acquiring the related data item inside
         * this method and should not keep a copy of it. If you need the position of an item later
         * on (e.g. in a click listener), use [ViewHolder.getAdapterPosition] which will
         * have the updated adapter position.
         *
         * Override [.onBindViewHolder] instead if Adapter can
         * handle efficient partial bind.
         *
         * @param holder The ViewHolder which should be updated to represent the contents of the
         * item at the given position in the data set.
         * @param position The position of the item within the adapter's data set.
         */
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val n = nodes[position]

            holder.nodeNameView.text = n.user?.longName ?: n.user?.id ?: "Unknown node"

            val ourNodeInfo = model.nodeDB.ourNodeInfo
            val distance = ourNodeInfo?.distanceStr(n)
            if (distance != null) {
                holder.distanceView.text = distance
                holder.distanceView.visibility = View.VISIBLE
            } else {
                holder.distanceView.visibility = View.INVISIBLE
            }

            renderBattery(n.batteryPctLevel, holder)

            holder.lastTime.text = getLastTimeValue(n)
        }

        private var nodes = arrayOf<NodeInfo>()

        /// Called when our node DB changes
        fun onNodesChanged(nodesIn: Collection<NodeInfo>) {
            nodes = nodesIn.toTypedArray()
            notifyDataSetChanged() // FIXME, this is super expensive and redraws all nodes
        }
    }

    private fun renderBattery(
        battery: Int?,
        holder: ViewHolder
    ) {

        val (image, text) = when (battery) {
            null -> Pair(R.drawable.ic_battery_full_24, "?")
            0 -> Pair(R.drawable.ic_power_plug_24, "")
            else -> Pair(R.drawable.ic_battery_full_24, "$battery%")
        }

        holder.batteryPctView.text = text
        holder.powerIcon.setImageDrawable(context?.let {
            ContextCompat.getDrawable(
                it,
                image
            )
        })
    }

    private fun getLastTimeValue(n: NodeInfo): String {
        var lastTimeText = "?"
        val currentTime = (System.currentTimeMillis()/1000).toInt()
        val threeDaysLong = 3 * 60*60*24

        //if the lastSeen is too old
        if (n.lastSeen < (currentTime - threeDaysLong))
            return lastTimeText

        try {
            val toLong: Long =  n.lastSeen.toLong()
            val long1000 = toLong * 1000L
            val date = Date(long1000)
            val timeFormat = DateFormat.getTimeFormat(context)

            lastTimeText = timeFormat.format(date)

        } catch (e: ParseException) {
            //
        }
        return lastTimeText
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = NodelistFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.nodeListView.adapter = nodesAdapter
        binding.nodeListView.layoutManager = LinearLayoutManager(requireContext())

        model.nodeDB.nodes.observe(viewLifecycleOwner, Observer { it ->
            nodesAdapter.onNodesChanged(it.values)
        })
    }
}


/*


                if (false) { // hide the firmware update button for now, it is kinda ugly and users don't need it yet
                    /// Create a software update button
                    val context = ContextAmbient.current
                    RadioInterfaceService.getBondedDeviceAddress(context)?.let { macAddress ->
                        Button(
                            onClick = {
                                SoftwareUpdateService.enqueueWork(
                                    context,
                                    SoftwareUpdateService.startUpdateIntent(macAddress)
                                )
                            }
                        ) {
                            Text(text = "Update firmware")
                        }
                    }
                }
            }
        }



        /* FIXME - doens't work yet - probably because I'm not using release keys
        // If account is null, then show the signin button, otherwise
        val context = ambient(ContextAmbient)
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null)
            Text("We have an account")
        else {
            Text("No account yet")
            if (context is Activity) {
                Button("Google sign-in", onClick = {
                    val signInIntent: Intent = UIState.googleSignInClient.signInIntent
                    context.startActivityForResult(signInIntent, MainActivity.RC_SIGN_IN)
                })
            }
        } */
    }
}

*/